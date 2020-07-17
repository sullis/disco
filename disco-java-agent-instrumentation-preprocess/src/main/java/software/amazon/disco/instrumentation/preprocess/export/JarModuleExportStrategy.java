/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package software.amazon.disco.instrumentation.preprocess.export;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.disco.instrumentation.preprocess.exceptions.ModuleExportException;
import software.amazon.disco.instrumentation.preprocess.exceptions.UnableToReadJarEntryException;
import software.amazon.disco.instrumentation.preprocess.instrumentation.InstrumentedClassState;
import software.amazon.disco.instrumentation.preprocess.loaders.modules.ModuleInfo;
import software.amazon.disco.instrumentation.preprocess.serialization.InstrumentationState;
import software.amazon.disco.instrumentation.preprocess.util.PreprocessConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Strategy to export transformed classes to a local Jar
 */
public class JarModuleExportStrategy implements ModuleExportStrategy {
    private static final Logger log = LogManager.getLogger(JarModuleExportStrategy.class);
    private static Path tempDir = null;

    private final String outputDir;

    /**
     * Constructor with the destination path provided.
     *
     * @param outputDir Absolute output path of the transformed jar. Default is the current folder where
     *                  the original jar is located.
     */
    public JarModuleExportStrategy(final String outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Constructor with no destination path provided. Transformed jar will replace the original file.
     */
    public JarModuleExportStrategy() {
        this.outputDir = null;
    }

    /**
     * Exports all transformed classes to a Jar file. A temporary Jar File will be created to store all
     * the transformed classes and then be renamed to replace the original Jar.
     *
     * @param moduleInfo           Information of the original Jar
     * @param instrumented         a map of instrumented classes with their bytecode
     * @param instrumentationState state to be passed to the runtime agent to dynamically skip already instrumented classes
     * @param suffix               suffix of the transformed package
     */
    @Override
    public void export(final ModuleInfo moduleInfo, final Map<String, InstrumentedClassState> instrumented, final InstrumentationState instrumentationState, final String suffix) {
        log.debug(PreprocessConstants.MESSAGE_PREFIX + "Saving changes to Jar");

        final File file = createTempFile(moduleInfo);

        buildOutputJar(moduleInfo, instrumented, file);
        moveTempFileToDestination(moduleInfo, suffix, file);
    }

    /**
     * Creates a Temporary file to store transformed classes and existing {@link JarEntry entries} from
     * the original {@link JarFile}. A temp folder with a 'disco' suffix will be created where these temp files will be stored if {@link #tempDir} is null.
     * A default suffix of '.temp' is used as implemented by the {@link Files} api.
     *
     * @param moduleInfo Information of the original Jar
     * @return created temp file
     */
    protected File createTempFile(final ModuleInfo moduleInfo) {
        try {
            if (tempDir == null) {
                tempDir = Files.createTempDirectory("disco");
            }

            return Files.createTempFile(tempDir, moduleInfo.getJarFile().getName(), null).toFile();
        } catch (IOException e) {
            throw new ModuleExportException("Failed to create temp Jar file", e);
        }
    }

    /**
     * Inserts all transformed classes into the temporary Jar file
     *
     * @param jarOS        {@link JarOutputStream} used to write entries to the output jar
     * @param instrumented a map of instrumented classes with their bytecode
     */
    protected void saveTransformedClasses(final JarOutputStream jarOS, final Map<String, InstrumentedClassState> instrumented) {
        for (Map.Entry<String, InstrumentedClassState> mapEntry : instrumented.entrySet()) {
            final String classPath = mapEntry.getKey();
            final InstrumentedClassState info = mapEntry.getValue();
            final JarEntry entry = new JarEntry(classPath + ".class");

            try {
                jarOS.putNextEntry(entry);
                jarOS.write(info.getClassBytes());
                jarOS.closeEntry();

                //todo: implemented serialization of InstrumentationState
            } catch (IOException e) {
                throw new ModuleExportException(classPath, e);
            }
        }
    }

    /**
     * Copies existing entries from the original Jar to the temporary Jar while skipping any
     * transformed classes.
     *
     * @param jarOS        {@link JarOutputStream} used to write entries to the output jar
     * @param moduleInfo   Information of the original Jar
     * @param instrumented a map of instrumented classes with their bytecode
     */
    protected void copyExistingJarEntries(final JarOutputStream jarOS, final ModuleInfo moduleInfo, final Map<String, InstrumentedClassState> instrumented) {
        for (Enumeration entries = moduleInfo.getJarFile().entries(); entries.hasMoreElements(); ) {
            final JarEntry entry = (JarEntry) entries.nextElement();
            final String keyToCheck = entry.getName().endsWith(".class") ? entry.getName().substring(0, entry.getName().lastIndexOf(".class")) : entry.getName();

            try {
                if (!instrumented.containsKey(keyToCheck)) {
                    if (entry.isDirectory()) {
                        jarOS.putNextEntry(entry);
                    } else {
                        copyJarEntry(jarOS, moduleInfo.getJarFile(), entry);
                    }
                }
            } catch (IOException e) {
                throw new ModuleExportException("Failed to copy class: " + entry.getName(), e);
            }
        }
    }

    /**
     * Builds the output jar by copying existing entries from the original and inserting transformed classes
     *
     * @param moduleInfo   Information of the original Jar
     * @param instrumented a map of instrumented classes with their bytecode
     * @param tempFile     file that the JarOutputStream will write to
     */
    protected void buildOutputJar(final ModuleInfo moduleInfo, final Map<String, InstrumentedClassState> instrumented, final File tempFile) {
        try (JarOutputStream jarOS = new JarOutputStream(new FileOutputStream(tempFile))) {
            copyExistingJarEntries(jarOS, moduleInfo, instrumented);
            saveTransformedClasses(jarOS, instrumented);
        } catch (IOException e) {
            throw new ModuleExportException("Failed to create output Jar file", e);
        }
    }

    /**
     * Copies a single {@link JarEntry entry} from the original Jar to the Temp Jar
     *
     * @param jarOS {@link JarOutputStream} used to write entries to the output jar
     * @param file  original {@link File jar} where the entry's binary data will be read
     * @param entry a single {@link JarEntry Jar entry}
     * @throws IOException
     */
    protected void copyJarEntry(final JarOutputStream jarOS, final JarFile file, final JarEntry entry) {
        try {
            final InputStream entryStream = file.getInputStream(entry);

            if (entryStream == null) {
                throw new UnableToReadJarEntryException(entry.getName(), null);
            }

            jarOS.putNextEntry(entry);

            final byte[] buffer = new byte[1024];

            int bytesRead;
            while ((bytesRead = entryStream.read(buffer)) != -1) {
                jarOS.write(buffer, 0, bytesRead);
            }
            jarOS.closeEntry();

        } catch (IOException e) {
            throw new UnableToReadJarEntryException(entry.getName(), e);
        }
    }

    /**
     * Move the temp file containing all existing entries and transformed classes to the
     * destination. If {@link #outputDir} is NOT specified, the original file will be replaced.
     *
     * @param moduleInfo Information of the original Jar
     * @param suffix     suffix to be appended to the transformed package
     * @param tempFile   output file to be moved to the destination path
     * @return {@link Path} of the overwritten file
     */
    protected Path moveTempFileToDestination(final ModuleInfo moduleInfo, final String suffix, final File tempFile) {
        try {
            final String destinationStr = outputDir == null ?
                    moduleInfo.getFile().getAbsolutePath()
                    : outputDir + "/" + moduleInfo.getFile().getName();

            final String destinationStrWithSuffix = suffix == null ?
                    destinationStr
                    : destinationStr.substring(0, destinationStr.lastIndexOf(PreprocessConstants.JAR_EXTENSION)) + suffix + PreprocessConstants.JAR_EXTENSION;

            final Path destination = Paths.get(destinationStrWithSuffix);
            destination.toFile().getParentFile().mkdirs();

            final boolean isOverride = outputDir == null && suffix == null;

            if (!isOverride && destination.toFile().exists()) {
                throw new ModuleExportException("Failed move transformed package to output directory, package with same name already present: " + destinationStrWithSuffix, null);
            }

            final Path filePath = Files.move(
                    Paths.get(tempFile.getAbsolutePath()),
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);

            if (filePath == null) {
                throw new ModuleExportException("Failed to replace existing jar file", null);
            }

            log.debug(PreprocessConstants.MESSAGE_PREFIX + "All transformed classes saved");

            return filePath;
        } catch (IOException e) {
            throw new ModuleExportException("Failed to replace existing jar file", e);
        }
    }
}
