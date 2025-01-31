/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.disco.instrumentation.preprocess.instrumentation;

import software.amazon.disco.instrumentation.preprocess.util.JarSigningVerificationOutcome;

/**
 * Default strategy to be configured if not explicitly supplied. Signed Jars will be statically instrumented. Beware that the hash value of the transformed class(es) are now
 * inconsistent with the value(s) recorded and signed in the manifest file. Verifying this transformed signed Jar using tools such as 'jarsigner' from the JDK will result in an
 * exit code of '1'.
 */
public class InstrumentSignedJarHandlingStrategy implements SignedJarHandlingStrategy {

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean skipJarLoading(final JarSigningVerificationOutcome outcome) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSimpleName(){
        return "instrument";
    }
}
