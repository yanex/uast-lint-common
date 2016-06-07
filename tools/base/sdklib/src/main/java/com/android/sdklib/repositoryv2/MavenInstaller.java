/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.sdklib.repositoryv2;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.repository.Revision;
import com.android.repository.api.Downloader;
import com.android.repository.api.LocalPackage;
import com.android.repository.api.ProgressIndicator;
import com.android.repository.api.RemotePackage;
import com.android.repository.api.RepoManager;
import com.android.repository.api.RepoPackage;
import com.android.repository.api.SettingsController;
import com.android.repository.impl.installer.PackageInstaller;
import com.android.repository.io.FileOp;
import com.android.repository.io.FileOpUtils;
import com.android.repository.util.InstallerUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.namespace.QName;

/**
 * A {@link PackageInstaller} that knows how to install Maven packages.
 */
public class MavenInstaller implements PackageInstaller {

    public static final String MAVEN_DIR_NAME = "m2repository";

    public static final String MAVEN_METADATA_FILE_NAME = "maven-metadata.xml";

    @Override
    public boolean uninstall(@NonNull LocalPackage p, @NonNull ProgressIndicator progress,
            @NonNull RepoManager manager, @NonNull FileOp fop) {
        fop.deleteFileOrFolder(p.getLocation());

        if (fop.exists(p.getLocation())) {
            progress.logWarning("Failed to uninstall " + p);
            return false;
        }
        manager.markInvalid();
        return removeVersion(p, fop, progress);
    }

    /**
     * Remove the specified package from the corresponding metadata file.
     */
    private static boolean removeVersion(@NonNull LocalPackage p, @NonNull FileOp fop,
            @NonNull ProgressIndicator progress) {
        PackageInfo info = parsePackageInfo(p.getLocation(), p);
        MavenMetadata metadata = parseMetadata(
                new File(p.getLocation().getParent(), MAVEN_METADATA_FILE_NAME), info, progress,
                fop);
        if (metadata == null) {
            return false;
        }
        metadata.versioning.versions.version.remove(info.version);
        return writeMetadata(new File(p.getLocation().getParentFile(), MAVEN_METADATA_FILE_NAME),
                progress, fop, metadata);
    }

    @Override
    public boolean install(@NonNull RemotePackage p, @NonNull Downloader downloader,
            @Nullable SettingsController settings, @NonNull ProgressIndicator progress,
            @NonNull RepoManager manager, @NonNull FileOp fop) {
        if (!p.getPath().startsWith(MAVEN_DIR_NAME)) {
            progress.logError("Maven package paths must start with " + MAVEN_DIR_NAME);
            return false;
        }
        URL url = InstallerUtil.resolveCompleteArchiveUrl(p, progress);
        if (url == null) {
            return false;
        }
        try {
            String path = p.getPath();
            path = path.replace(RepoPackage.PATH_SEPARATOR, File.separatorChar);
            File dest = new File(manager.getLocalPath(), path);
            if (!InstallerUtil.checkValidPath(dest, manager, progress)) {
                return false;
            }

            File in = downloader.downloadFully(url, settings, progress);

            File out = FileOpUtils.getNewTempDir("BasicInstaller", fop);
            if (out == null || !fop.mkdirs(out)) {
                throw new IOException("Failed to create temp dir");
            }
            fop.deleteOnExit(out);
            InstallerUtil.unzip(in, out, fop, p.getArchive().getComplete().getSize(), progress);
            fop.delete(in);

            // Archives must contain a single top-level directory.
            File[] topDirContents = fop.listFiles(out);
            if (topDirContents.length != 1) {
                throw new IOException("Archive didn't have single top level directory");
            }
            File packageRoot = topDirContents[0];

            InstallerUtil.writePackageXml(p, packageRoot, manager, fop, progress);

            // Move the final unzipped archive into place.
            FileOpUtils.safeRecursiveOverwrite(packageRoot, dest, fop, progress);

            PackageInfo info = parsePackageInfo(dest, p);
            addVersion(new File(dest.getParentFile(), MAVEN_METADATA_FILE_NAME), info, progress,
                    fop);
            manager.markInvalid();
            return true;
        } catch (IOException e) {
            progress.logWarning("An error occurred during installation.", e);
        }

        return false;
    }

    /**
     * Gets the version, artifact Id, and group Id from the given path and package.
     */
    private static PackageInfo parsePackageInfo(@NonNull File path, @NonNull RepoPackage p) {
        PackageInfo result = new PackageInfo();
        result.version = path.getName();
        result.artifactId = path.getParentFile().getName();
        result.groupId = p.getPath().substring(p.getPath().indexOf(RepoPackage.PATH_SEPARATOR) + 1,
                p.getPath().lastIndexOf(result.artifactId) - 1)
                .replace(RepoPackage.PATH_SEPARATOR, '.');
        return result;
    }

    /**
     * Adds the specified packageInfo to the corresponding maven metadata file.
     */
    private static boolean addVersion(@NonNull File metadataFile, @NonNull PackageInfo info,
            @NonNull ProgressIndicator progress, FileOp fop) {
        MavenMetadata metadata = parseMetadata(metadataFile, info, progress, fop);

        if (metadata == null) {
            return false;
        }

        metadata.versioning.versions.version.add(info.version);
        return writeMetadata(metadataFile, progress, fop, metadata);
    }

    /**
     * Writes out a {@link MavenMetadata} to the specified location.
     */
    private static boolean writeMetadata(@NonNull File file, @NonNull ProgressIndicator progress,
            @NonNull FileOp fop, @NonNull MavenMetadata metadata) {
        Revision max = null;
        for (String s : metadata.versioning.versions.version) {
            Revision rev = Revision.parseRevision(s);
            if (max == null || (!rev.isPreview() && rev.compareTo(max) > 0)) {
                max = rev;
            }
        }
        if (max != null) {
            metadata.versioning.release = max.toString();
        }
        metadata.versioning.lastUpdated = System.currentTimeMillis();
        Marshaller marshaller;
        try {
            JAXBContext context;
            try {
                context = JAXBContext.newInstance(MavenMetadata.class);
            } catch (JAXBException e) {
                // Shouldn't happen
                progress.logError("Failed to create JAXBContext", e);
                return false;
            }
            marshaller = context.createMarshaller();
        } catch (JAXBException e) {
            // Shouldn't happen
            progress.logError("Failed to create Marshaller", e);
            return false;
        }
        ByteArrayOutputStream metadataOutBytes = new ByteArrayOutputStream();
        try {
            marshaller.marshal(
                    new JAXBElement<MavenMetadata>(new QName("metadata"), MavenMetadata.class,
                            metadata), metadataOutBytes);
        } catch (JAXBException e) {
            progress.logWarning("Failed to write maven metadata: ", e);
            return false;
        }
        OutputStream metadataOutFile = null;
        try {
            metadataOutFile = fop.newFileOutputStream(file);
            metadataOutFile.write(metadataOutBytes.toByteArray());
        } catch (FileNotFoundException e) {
            progress.logWarning("Failed to write metadata file.", e);
            return false;
        } catch (IOException e) {
            progress.logWarning("Failed to write metadata file.", e);
            return false;
        } finally {
            if (metadataOutFile != null) {
                try {
                    metadataOutFile.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        if (!writeHashFile(file, "MD5", progress, metadataOutBytes, fop)) {
            return false;
        }
        if (!writeHashFile(file, "SHA1", progress, metadataOutBytes, fop)) {
            return false;
        }
        return true;
    }

    /**
     * Parses a {@link MavenMetadata} from the specified file, and verifies that it contains the
     * specified artifact, or creates a new one if none exists.
     */
    private static MavenMetadata parseMetadata(@NonNull File file, @NonNull PackageInfo info,
            @NonNull ProgressIndicator progress, @NonNull FileOp fop) {
        MavenMetadata metadata;
        if (fop.exists(file)) {
            metadata = unmarshalMetadata(file, progress, fop);
            if (metadata == null) {
                return null;
            }
            if (!metadata.artifactId.equals(info.artifactId)) {
                progress.logWarning(
                        "New artifact id '" + info.artifactId + "' doesn't match existing '"
                                + metadata.artifactId);
                return null;
            }
            if (!metadata.groupId.equals(info.groupId)) {
                progress.logWarning("New group id '" + info.groupId + "' doesn't match existing '"
                        + metadata.groupId);
                return null;
            }
        } else {
            metadata = new MavenMetadata();
            metadata.artifactId = info.artifactId;
            metadata.groupId = info.groupId;
            metadata.versioning = new MavenMetadata.Versioning();
            metadata.versioning.versions = new MavenMetadata.Versioning.Versions();
            metadata.versioning.versions.version = Lists.newArrayList();
        }
        return metadata;
    }

    /**
     * Attempts to read a {@link MavenMetadata} from the specified file.
     */
    @VisibleForTesting
    static MavenMetadata unmarshalMetadata(@NonNull File metadataFile,
            @NonNull ProgressIndicator progress, @NonNull FileOp fop) {
        JAXBContext context;
        try {
            context = JAXBContext.newInstance(MavenMetadata.class);
        } catch (JAXBException e) {
            // Shouldn't happen
            progress.logError("Failed to create JAXBContext", e);
            return null;
        }
        Unmarshaller unmarshaller;
        MavenMetadata result;
        try {
            unmarshaller = context.createUnmarshaller();
        } catch (JAXBException e) {
            // Shouldn't happen
            progress.logError("Failed to create unmarshaller", e);
            return null;
        }
        InputStream metadataInputStream;
        try {
            metadataInputStream = fop.newFileInputStream(metadataFile);
        } catch (FileNotFoundException e) {
            progress.logWarning("Couldn't open metadata file", e);
            return null;
        }
        try {
            result = (MavenMetadata) unmarshaller.unmarshal(metadataInputStream);
        } catch (JAXBException e) {
            progress.logWarning("Couldn't parse maven metadata file: " + metadataFile);
            return null;
        }
        return result;
    }

    /**
     * Writes a file containing a hash of the given file using the specified algorithm.
     */
    private static boolean writeHashFile(@NonNull File file, @NonNull String algorithm,
            @NonNull ProgressIndicator progress, @NonNull ByteArrayOutputStream metadataOutBytes,
            @NonNull FileOp fop) {
        File md5File = new File(file.getParent(),
                MAVEN_METADATA_FILE_NAME + "." + algorithm.toLowerCase());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // Shouldn't happen
            progress.logError(algorithm + " algorithm not found", e);
            return false;
        }
        OutputStream md5OutFile;
        try {
            md5OutFile = fop.newFileOutputStream(md5File);
        } catch (FileNotFoundException e) {
            progress.logWarning("Failed to open " + algorithm + " file");
            return false;
        }
        try {
            md5OutFile.write(
                    DatatypeConverter.printHexBinary(digest.digest(metadataOutBytes.toByteArray()))
                            .getBytes());
        } catch (IOException e) {
            progress.logWarning("Failed to write " + algorithm + " file");
            return false;
        } finally {
            try {
                md5OutFile.close();
            } catch (IOException e) {
                // ignore
            }
        }
        return true;
    }

    /**
     * jaxb-usable class for marshalling/unmarshalling maven metadata files.
     */
    @VisibleForTesting
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlRootElement(name = "metadata")
    static class MavenMetadata {

        protected String groupId;

        protected String artifactId;

        protected MavenMetadata.Versioning versioning;

        @XmlAccessorType(XmlAccessType.FIELD)
        public static class Versioning {

            protected String release;

            protected MavenMetadata.Versioning.Versions versions;

            protected long lastUpdated;

            @XmlAccessorType(XmlAccessType.FIELD)
            public static class Versions {

                protected List<String> version;
            }
        }
    }

    /**
     * Simple container for maven artifact version info.
     */
    private static class PackageInfo {

        public String artifactId;

        public String groupId;

        public String version;
    }
}
