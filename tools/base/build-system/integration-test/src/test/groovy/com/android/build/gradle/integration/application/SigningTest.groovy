/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.category.DeviceTests
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.integration.common.utils.ModelHelper
import com.android.build.gradle.integration.common.utils.SigningConfigHelper
import com.android.builder.model.AndroidArtifact
import com.android.builder.model.AndroidProject
import com.android.builder.model.SigningConfig
import com.android.builder.model.Variant
import com.google.common.collect.ImmutableList
import com.google.common.io.Resources
import groovy.transform.CompileStatic
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThatZip
import static com.android.builder.core.BuilderConstants.DEBUG
import static com.android.builder.core.BuilderConstants.RELEASE
import static com.android.builder.model.AndroidProject.PROPERTY_SIGNING_KEY_ALIAS
import static com.android.builder.model.AndroidProject.PROPERTY_SIGNING_KEY_PASSWORD
import static com.android.builder.model.AndroidProject.PROPERTY_SIGNING_STORE_FILE
import static com.android.builder.model.AndroidProject.PROPERTY_SIGNING_STORE_PASSWORD
/**
 * Integration test for all signing-related features.
 */
@CompileStatic
@RunWith(FilterableParameterized)
class SigningTest {

    public static final String STORE_PASSWORD = "store_password"

    public static final String ALIAS_NAME = "alias_name"

    public static final String KEY_PASSWORD = "key_password"

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        def parameters = [
                ["rsa_keystore.jks", "CERT.RSA", 1] as Object[],
        ]

        // These are not available on 1.6. To test, run the following (with proper JAVA_HOME):
        // JAVA_FOR_TESTS=1.8 ./gradlew :b:i:testPrebuilts --tests=*.SigningTest
        if (!System.getProperty("java.version").startsWith("1.6")) {
            parameters.add(["dsa_keystore.jks", "CERT.DSA", 1] as Object[])
            parameters.add(["ec_keystore.jks", "CERT.EC", 18] as Object[])
        }

        return parameters
    }

    @Parameterized.Parameter(0)
    public String keystoreName

    @Parameterized.Parameter(1)
    public String certEntryName

    @Parameterized.Parameter(2)
    public int minSdkVersion

    @Rule
    public GradleTestProject project = GradleTestProject.builder()
            .fromTestApp(HelloWorldApp.noBuildFile())
            .create()

    private File keystore

    @Before
    public void setUp() throws Exception {
        keystore = project.file("the.keystore")


        createKeystoreFile(keystoreName, keystore)

        project.buildFile << """
                apply plugin: 'com.android.application'

                android {
                    compileSdkVersion $GradleTestProject.DEFAULT_COMPILE_SDK_VERSION
                    buildToolsVersion "$GradleTestProject.DEFAULT_BUILD_TOOL_VERSION"

                    defaultConfig {
                        minSdkVersion ${minSdkVersion}
                    }

                    signingConfigs {
                        customDebug {
                            storeFile file("${keystore.name}")
                            storePassword "$STORE_PASSWORD"
                            keyAlias "$ALIAS_NAME"
                            keyPassword "$KEY_PASSWORD"
                        }
                    }

                    buildTypes {
                        debug {
                            signingConfig signingConfigs.customDebug
                        }

                        customSigning {
                            initWith release
                        }
                    }

                    applicationVariants.all { variant ->
                        if (variant.buildType.name == "customSigning") {
                            variant.outputsAreSigned = true
                            // This usually means there is a task that generates the final outputs
                            // and variant.outputs*.outputFile is set to point to these files.
                        }
                    }
                }
"""

    }

    private void createKeystoreFile(String resourceName, File keystore) {
        def keystoreBytes =
                Resources.toByteArray(
                        Resources.getResource(SigningTest, "SigningTest/" + resourceName))
        keystore << keystoreBytes
    }

    @Test
    void "signing DSL"() throws Exception {
        project.execute("assembleDebug")
        assertThatZip(project.getApk("debug")).contains("META-INF/$certEntryName")
    }

    @Test
    void "assemble with injected signing config"() {
        // add prop args for signing override.
        List<String> args = ImmutableList.of(
                "-P" + PROPERTY_SIGNING_STORE_FILE + "=" + keystore.getPath(),
                "-P" + PROPERTY_SIGNING_STORE_PASSWORD + "=" + STORE_PASSWORD,
                "-P" + PROPERTY_SIGNING_KEY_ALIAS + "=" + ALIAS_NAME,
                "-P" + PROPERTY_SIGNING_KEY_PASSWORD + "=" + KEY_PASSWORD)

        project.execute(args, "assembleRelease")

        // Check for signing file inside the archive.
        assertThatZip(project.getApk("release")).contains("META-INF/$certEntryName")
    }

    @Test
    void "check custom signing"() throws Exception {
        Collection<Variant> variants = project.singleModel.variants

        for (Variant variant : variants) {
            // Release variant doesn't specify the signing config, so it should not be considered
            // signed.
            if (variant.getName().equals("release")) {
                assertThat(variant.mainArtifact.signed).named(variant.name).isFalse()
            }

            // customSigning is identical to release, but overrides the signing check.
            if (variant.getName().equals("customSigning")) {
                assertThat(variant.mainArtifact.signed).named(variant.name).isTrue()
            }
        }
    }

    @Test
    public void "signing configs model"() {
        def model = project.getSingleModel()

        Collection<SigningConfig> signingConfigs = model.signingConfigs
        assertThat(signingConfigs.collect {it.name}).containsExactly("debug", "customDebug")

        SigningConfig debugSigningConfig = ModelHelper.getSigningConfig(signingConfigs, DEBUG)
        new SigningConfigHelper(debugSigningConfig, DEBUG, true).test()

        SigningConfig mySigningConfig = ModelHelper.getSigningConfig(signingConfigs, "customDebug")
        new SigningConfigHelper(mySigningConfig, "customDebug", true)
                .setStoreFile(keystore)
                .setStorePassword(STORE_PASSWORD)
                .setKeyAlias(ALIAS_NAME)
                .setKeyPassword(KEY_PASSWORD)
                .test()

        Variant debugVariant = ModelHelper.getVariant(model.variants, DEBUG)
        assertThat(debugVariant.mainArtifact.signingConfigName).isEqualTo("customDebug")
        Collection<AndroidArtifact> debugExtraAndroidArtifacts = debugVariant.getExtraAndroidArtifacts()
        AndroidArtifact androidTestArtifact = ModelHelper.getAndroidArtifact(
                debugExtraAndroidArtifacts,
                AndroidProject.ARTIFACT_ANDROID_TEST)

        assertThat(androidTestArtifact.signingConfigName).isEqualTo("customDebug")

        Variant releaseVariant = ModelHelper.getVariant(model.variants, RELEASE)
        assertThat(releaseVariant.mainArtifact.signingConfigName).isNull()
    }

    @Test
    public void 'signingReport task'() throws Exception {
        project.execute("signingReport")
    }

    @Test
    public void 'SHA algorithm change'() throws Exception {
        File apk = project.getApk("debug")

        if (minSdkVersion < 18) {
            project.execute("assembleDebug")

            assertThatApk(apk).containsFileWithMatch("META-INF/CERT.SF", "SHA1-Digest");
            assertThatApk(apk).containsFileWithoutContent("META-INF/CERT.SF", "SHA-256-Digest");
            assertThatApk(apk).containsFileWithMatch("META-INF/MANIFEST.MF", "SHA1-Digest");
            assertThatApk(apk).containsFileWithoutContent("META-INF/MANIFEST.MF", "SHA-256-Digest");

            TestFileUtils.searchAndReplace(project.buildFile, "minSdkVersion \\d+", "minSdkVersion 18")
        }

        project.execute("assembleDebug")

        assertThatApk(apk).containsFileWithMatch("META-INF/CERT.SF", "SHA-256-Digest");
        assertThatApk(apk).containsFileWithoutContent("META-INF/CERT.SF", "SHA1-Digest");
        assertThatApk(apk).containsFileWithMatch("META-INF/MANIFEST.MF", "SHA-256-Digest");
        assertThatApk(apk).containsFileWithoutContent("META-INF/MANIFEST.MF", "SHA1-Digest");
    }

    @Test
    @Category(DeviceTests)
    public void 'SHA algorithm change - on device'() throws Exception {
        project.executeConnectedCheck()

        if (minSdkVersion < 18) {
            TestFileUtils.searchAndReplace(project.buildFile, "minSdkVersion \\d+", "minSdkVersion 18")
            project.executeConnectedCheck()
        }
    }
}
