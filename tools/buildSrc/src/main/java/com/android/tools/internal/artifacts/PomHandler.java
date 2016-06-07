/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.tools.internal.artifacts;

import com.google.common.io.Closeables;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class able to parse a POM file, and provide some information from it:
 * - Is the artifact relocated (and what's the relocation artifact)
 * - parent POM
 * - packaging
 */
public class PomHandler {

    private final File pomFile;
    private Document document = null;

    private final static class FakeModuleVersionIdentifier implements ModuleVersionIdentifier {

        private final String group;
        private final String name;
        private final String version;

        FakeModuleVersionIdentifier(String group, String name, String version) {

            this.group = group;
            this.name = name;
            this.version = version;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public String getGroup() {
            return group;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ModuleIdentifier getModule() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return getGroup() + ":" + getName() + ":" + getVersion();
        }
    }

    public static class License {
        private final String name;
        private final String url;
        private final String comments;

        public License(String name, String url, String comments) {
            this.name = name;
            this.url = url;
            this.comments = comments;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public String getComments() {
            return comments;
        }
    }

    public PomHandler(File pomFile) {
        this.pomFile = pomFile;
    }

    public ModuleVersionIdentifier getArtifactId() throws IOException {
        Document document = getDocument();
        Node rootNode = document.getDocumentElement();

        ModuleVersionIdentifier version = readArtifactAddress(rootNode);
        if (version.getGroup() == null || version.getVersion() == null) {
            ModuleVersionIdentifier parentVersion = getParentPom();

            return new FakeModuleVersionIdentifier(
                    version.getGroup() != null ? version.getGroup() : parentVersion.getGroup(),
                    version.getName(),
                    version.getVersion() != null ? version.getVersion() : parentVersion.getVersion());
        }

        return version;
    }

    ModuleVersionIdentifier getRelocation() throws IOException {
        Document document = getDocument();
        Node rootNode = document.getDocumentElement();

        Node node = findNode(rootNode, "distributionManagement");
        if (node == null) {
            return null;
        }

        node = findNode(node, "relocation");
        if (node == null) {
            return null;
        }

        // ok there is a relocation. Let's find out the current artifact address
        ModuleVersionIdentifier original = readArtifactAddress(rootNode);

        // now read the relocation info
        ModuleVersionIdentifier relocation = readArtifactAddress(node);

        // merge them in case only part of the address is changed
        return new FakeModuleVersionIdentifier(
                relocation.getGroup() != null ? relocation.getGroup() : original.getGroup(),
                relocation.getName() != null ? relocation.getName() : original.getName(),
                relocation.getVersion() != null ? relocation.getVersion() : original.getVersion());
    }

    private ModuleVersionIdentifier readArtifactAddress(Node parentNode) {
        String group = null;
        String name = null;
        String version = null;

        Node node = findNode(parentNode, "groupId");
        if (node != null) {
            group = getTextNode(node);
        }

        node = findNode(parentNode, "artifactId");
        if (node != null) {
            name = getTextNode(node);
        }

        node = findNode(parentNode, "version");
        if (node != null) {
            version = getTextNode(node);
        }

        return new FakeModuleVersionIdentifier(group, name, version);
    }

    public ModuleVersionIdentifier getParentPom() throws IOException {
        Document document = getDocument();
        Node rootNode = document.getDocumentElement();

        Node node = findNode(rootNode, "parent");
        if (node == null) {
            return null;
        }

        // there is a parent. Look for the rest of the nodes.
        final Node groupId = findNode(node, "groupId");
        final Node artifactId = findNode(node, "artifactId");
        final Node version = findNode(node, "version");

        if (groupId == null || artifactId == null || version == null) {
            return null;
        }

        return new FakeModuleVersionIdentifier(
                getTextNode(groupId),
                getTextNode(artifactId),
                getTextNode(version));
    }

    public String getPackaging() throws IOException {
        Document document = getDocument();
        Node rootNode = document.getDocumentElement();

        Node node = findNode(rootNode, "packaging");
        if (node == null) {
            return null;
        }

        return getTextNode(node);
    }

    public List<License> getLicenses() throws IOException {
        Document document = getDocument();
        Node rootNode = document.getDocumentElement();

        Node licensesNode = findNode(rootNode, "licenses");
        if (licensesNode == null) {
            return Collections.emptyList();
        }

        NodeList licenseNodes = licensesNode.getChildNodes();

        List<License> list = new ArrayList<License>();

        for (int i = 0, n = licenseNodes.getLength(); i < n; i++) {
            Node licenseNode = licenseNodes.item(i);

            if (licenseNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            if ("license".equals(licenseNode.getLocalName())) {
                final Node name = findNode(licenseNode, "name");
                final Node url = findNode(licenseNode, "url");
                final Node comments = findNode(licenseNode, "comments");

                String nameValue = name != null ? getTextNode(name) : null;
                String urlValue = url != null ? getTextNode(url) : null;
                String commentsValue = comments != null ? getTextNode(comments) : null;

                if (nameValue != null) {
                    list.add(new License(nameValue, urlValue, commentsValue));
                }
            }
        }

        return list;
    }

    private Document getDocument() throws IOException {
        if (document == null) {
            document = parseDocument(pomFile);
        }

        return document;
    }

    private Node findNode(Node rootNode, String name) {
        NodeList nodes = rootNode.getChildNodes();

        for (int i = 0, n = nodes.getLength(); i < n; i++) {
            Node node = nodes.item(i);

            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            if (name.equals(node.getLocalName())) {
                return node;
            }
        }

        return null;
    }

    private String getTextNode(Node rootNode) {
        NodeList nodes = rootNode.getChildNodes();

        for (int i = 0, n = nodes.getLength(); i < n; i++) {
            Node node = nodes.item(i);

            if (node.getNodeType() != Node.TEXT_NODE) {
                continue;
            }

            return node.getNodeValue();
        }

        return null;
    }

    /**
     * Loads the DOM for a given file and returns a {@link org.w3c.dom.Document} object.
     * @param file the file to parse
     * @return a Document object.
     * @throws java.io.IOException
     */
    static Document parseDocument(File file) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        BufferedInputStream stream = new BufferedInputStream(new FileInputStream(file));
        InputSource is = new InputSource(stream);
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(is);
        } catch (ParserConfigurationException e) {
            throw new IOException(e);
        } catch (SAXException e) {
            throw new IOException(e);
        } finally {
            Closeables.close(stream, true /* swallowIOException */);
        }
    }
}
