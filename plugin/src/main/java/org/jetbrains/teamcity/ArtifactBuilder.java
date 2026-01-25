package org.jetbrains.teamcity;

import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.jetbrains.teamcity.agent.*;
import org.jetbrains.teamcity.data.ArtifactNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jetbrains.teamcity.data.ArtifactNode.ArtifactNodeType.*;

@Data
public class ArtifactBuilder {
    private final Log log;
    private final WorkflowUtil util;

    public List<Path> build(List<AssemblyContext> assemblyContexts, String intellijProjectPath) {
        getLog().debug("Building " + assemblyContexts);
        List<Path> generatedArtifacts = new ArrayList<>();
        for (AssemblyContext ac: assemblyContexts) {
            try {
                generatedArtifacts.add(generateAssembly(ac, intellijProjectPath));
            } catch (Exception e) {
                getLog().warn("Error while assembling " + assemblyContexts, e);
            }
        }
        return generatedArtifacts;
    }

    public Path generateAssembly(AssemblyContext ac, String intellijProjectPath) throws ParserConfigurationException, IOException, TransformerException {
        Path intellijProject = util.getProject().getBasedir().toPath();
        if (intellijProjectPath != null && !"".equalsIgnoreCase(intellijProjectPath)) {
            intellijProject = intellijProject.resolve(intellijProjectPath).normalize();
        } else {
            if (util.getProject().getParent() != null) {
                intellijProject = util.getProject().getParent().getBasedir().toPath();
            }
        }
        IJArtifactParameters params = new IJArtifactParameters();
        params.setArtifactPath(intellijProject.relativize(ac.getRoot()));
        params.setProjectRoot(intellijProject);

        ArtifactNode root = new ArtifactNode("", DIR, null);
        for (PathSet ps: ac.getPaths()) {
            ArtifactNode location = getArtifactNode(root, ps);
            for (PathEntry pe: ps.getPathEntryList()) {
                ArtifactNode.ArtifactNodeType type = FILE;
                if (pe instanceof DependencyPathEntry)
                    type = DEPENDENCY;
                else if (pe instanceof FilePathEntry)
                    type = FILE;
                else if (pe instanceof DirCopyPathEntry)
                    type = DIR_COPY;
                else if (pe instanceof ArtifactPathEntry)
                    type = ARTIFACT;
                else if (pe instanceof CompressedPathEntry)
                    type = COMPRESSED_FILE;
                location.getChilds().add(new ArtifactNode(pe.getName(), type, pe));
            }
        }
        String artifactFileName = ac.getName().replaceAll("[^\\w\\d]", "_") + ".xml";
        Path destinationFile = intellijProject.resolve(".idea").resolve("artifacts").resolve(artifactFileName);
        util.createDir(destinationFile.getParent());
        Path source = util.getWorkDirectory().resolve(artifactFileName);
        serializeIntoXml(root, ac, params, source);
        FileUtils.copyFile(source.toFile(), destinationFile.toFile());
        return destinationFile;
    }

    private void serializeIntoXml(ArtifactNode root, AssemblyContext ac, IJArtifactParameters params, Path destinationFile) throws ParserConfigurationException, TransformerException, IOException {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

        // root elements
        Document doc = docBuilder.newDocument();

        Element rootElement = doc.createElement("component");
        rootElement.setAttribute("name", "ArtifactManager");
        doc.appendChild(rootElement);
        Element artifact = doc.createElement("artifact");
        artifact.setAttribute("name", ac.getName());
        rootElement.appendChild(artifact);
        Element outputPath = doc.createElement("output-path");
        outputPath.setTextContent("$PROJECT_DIR$/"+params.getArtifactPath());
        artifact.appendChild(outputPath);
        Element aRoot = doc.createElement("root");
        aRoot.setAttribute("id", "root");
        artifact.appendChild(aRoot);
        genTree(doc, aRoot, root, params.getProjectRoot());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeXml(doc, baos);
        baos.close();
        String xmlContent = baos.toString(StandardCharsets.UTF_8.name());
        Jdk8Compat.writeStringToFile(destinationFile, baos.toByteArray());
    }

    private static void writeXml(Document doc,
                                 OutputStream output)
            throws TransformerException {

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(output);

        transformer.transform(source, result);

    }
    private void genTree(Document doc, Element aRoot, ArtifactNode root, Path ideaProjectRoot) {
        for (ArtifactNode artifactNode: root.getChilds()) {
            Element element = createElement(doc, aRoot, artifactNode, ideaProjectRoot);
            genTree(doc, element, artifactNode, ideaProjectRoot);
        }
    }

    private Element createElement(Document doc, Element parent, ArtifactNode artifactNode, Path ideaProjectRoot) {
        Element element = doc.createElement("element");
        parent.appendChild(element);
        if (artifactNode.getType() == DIR) {
            setIdName(element, "directory", artifactNode.getPath());
        } else if (artifactNode.getType() == DEPENDENCY) {
            if (artifactNode.getInfo() instanceof DependencyPathEntry) {
                DependencyPathEntry dpe = (DependencyPathEntry) artifactNode.getInfo();
                if (dpe.isReactorProject()) {
                    setIdName(element, "archive", artifactNode.getPath());
                    Element moduleOutput = doc.createElement("element");
                    moduleOutput.setAttribute("id", "module-output");
                    moduleOutput.setAttribute("name", dpe.getArtifact().getArtifactId());
                    element.appendChild(moduleOutput);
                } else {
                    setIdName(element, "library", getMavenLibraryName(dpe.getArtifact()));
                    element.setAttribute("level", "project");
                }
            }
        } else if (artifactNode.getType() == ARTIFACT) {
            if (artifactNode.getInfo() instanceof ArtifactPathEntry) {
                ArtifactPathEntry ape = (ArtifactPathEntry) artifactNode.getInfo();
                Element artifact;
                if (ape.getName() != null) {
                    setIdName(element, "archive", ape.getName());
                    artifact = doc.createElement("element");
                    element.appendChild(artifact);
                } else {
                    artifact = element;
                }
                setIdName(artifact, "artifact", null);
                artifact.setAttribute("artifact-name", ape.getArtifactName());
            }
        } else if (artifactNode.getType() == FILE) {
            if (artifactNode.getInfo() instanceof FilePathEntry) {
                setIdName(element, "file-copy", null);
                FilePathEntry fpe = (FilePathEntry) artifactNode.getInfo();
                element.setAttribute("path", "$PROJECT_DIR$/"+relativeTo(fpe.getResolved(), ideaProjectRoot));
                if (fpe.getName() != null)
                    element.setAttribute("output-file-name", fpe.getName());

            }
        } else if (artifactNode.getType() == COMPRESSED_FILE) {
            /**
             *           <element id="archive" name="dotNet-generic-runner.jar">
             *             <element id="directory" name="buildServerResources">
             *               <element id="dir-copy" path="$PROJECT_DIR$/dotNet/generic-runner-server/resources" />
             *             </element>
             *           </element>
             */
            if (artifactNode.getInfo() instanceof CompressedPathEntry) {
                CompressedPathEntry fpe = (CompressedPathEntry) artifactNode.getInfo();
                setIdName(element, "archive", fpe.getName());
                Element e = element;
                String prefixInArchive = fpe.getPrefixInArchive();
                if (Jdk8Compat.isNotEmpty(prefixInArchive))
                    e = newElement(element, "directory", prefixInArchive);
                for(Path resolved: fpe.resolve()) {
                    Element dirCopy = newElement(e, "dir-copy", null);
                    dirCopy.setAttribute("path", "$PROJECT_DIR$/" + relativeTo(resolved, ideaProjectRoot));
                }
            }
        } else if (artifactNode.getType() == DIR_COPY) {
            if (artifactNode.getInfo() instanceof DirCopyPathEntry) {
                setIdName(element, "dir-copy", null);
                DirCopyPathEntry fpe = (DirCopyPathEntry) artifactNode.getInfo();
                element.setAttribute("path", "$PROJECT_DIR$/"+relativeTo(fpe.getResolved(), ideaProjectRoot));
            }
        }
        return element;
    }

    private Element newElement(Element element, String id, String name) {
        Document doc = element.getOwnerDocument();
        Element e = doc.createElement("element");
        element.appendChild(e);
        setIdName(e, id, name);
        return e;
    }

    private String relativeTo(Path resolved, Path ideaProjectRoot) {
        return ideaProjectRoot.relativize(resolved).toString();
    }

    private void setIdName(Element element, String id, String name) {
        if (id != null)
            element.setAttribute("id", id);
        if (name != null)
            element.setAttribute("name", name);
    }

    private String getMavenLibraryName(Artifact artifact) {
        return String.format("Maven: %s:%s:%s", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    }

    private ArtifactNode getArtifactNode(ArtifactNode root, PathSet ps) {
        ArtifactNode current = root;
        for(int i = 0; i < ps.getDir().getNameCount(); i++) {
            String name = ps.getDir().getName(i).toString();
            current = findOrCreateDir(current, name);
        }
        return current;
    }

    private ArtifactNode findOrCreateDir(ArtifactNode current, String name) {
        for (ArtifactNode an: current.getChilds()) {
            if (Objects.equals(an.getPath(), name)) {
                return an;
            }
        }
        if (Jdk8Compat.isNotEmpty(name)) { // skip empty directories
            ArtifactNode an = new ArtifactNode(name, DIR, null);
            current.getChilds().add(an);
            return an;
        } else
            return current;
    }


}
