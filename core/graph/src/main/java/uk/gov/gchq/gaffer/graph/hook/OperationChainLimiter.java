/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.gov.gchq.gaffer.graph.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.gchq.gaffer.commonutil.CloseableUtil;
import uk.gov.gchq.gaffer.commonutil.CommonConstants;
import uk.gov.gchq.gaffer.commonutil.exception.UnauthorisedException;
import uk.gov.gchq.gaffer.operation.Operation;
import uk.gov.gchq.gaffer.operation.OperationChain;
import uk.gov.gchq.gaffer.user.User;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * An <code>OperationChainLimiter</code> is a {@link GraphHook} that checks a
 * user is authorised to execute an operation chain based on that user's maximum chain score and the configured score value for each operation in the chain.
 * This class requires a map of operation scores, these can be added using setOpScores(Map<Class,Integer>) or
 * addOpScore(Class, Integer...). Alternatively a properties file can be provided.
 * When using a properties file the last entry in the file that an operation can be assigned to will be the score that is used for that operation.
 * containing the operations and the score.
 * E.g if you put gaffer.operation.impl.add.AddElements = 8
 * And then gaffer.operation.impl.add = 1
 * The add elements will have a score of 1 not 8.
 * So make sure to write your properties file in class hierarchical order.
 *
 * This class also requires a map of authorisation scores,
 * this is the score value someone with that auth can have, the maximum score value of a users auths is used.
 * These can be added using setAuthScores(Map<String, Integer>) or
 * addAuthScore(String, Integer). Alternatively a properties file can be provided
 * containing the authorisations and the score.
 */
public class OperationChainLimiter implements GraphHook {
    private static final int DEFAULT_OPERATION_SCORE = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger(OperationChainLimiter.class);
    private final Map<Class<? extends Operation>, Integer> operationScores = new LinkedHashMap<>();
    private final Map<String, Integer> authScores = new HashMap<>();

    /**
     * Default constructor.
     * Use setOpScores or addOpScore to add operation scores.
     * And Use setAuthScores or
     * addAuthScore to add Authorisation scores.
     */
    public OperationChainLimiter() {
    }

    /**
     * Constructs an {@link OperationAuthoriser} with the authorisations
     * defined in the property file from the {@link Path} provided.
     *
     * @param operationScorePropertiesFileLocation         path to operation scores property file
     * @param operationAuthorisationScoreLimitFileLocation path to authorisation scores property file
     */
    public OperationChainLimiter(final Path operationScorePropertiesFileLocation, final Path operationAuthorisationScoreLimitFileLocation) {
        this(readEntries(operationScorePropertiesFileLocation), readEntries(operationAuthorisationScoreLimitFileLocation));
    }


    /**
     * Constructs an {@link OperationAuthoriser} with the authorisations
     * defined in the property file from the {@link InputStream} provided.
     *
     * @param operationScorePropertiesStream         input stream of operation scores property file
     * @param operationAuthorisationScoreLimitStream input stream of authorisation scores property file
     */
    public OperationChainLimiter(final InputStream operationScorePropertiesStream, final InputStream operationAuthorisationScoreLimitStream) {
        this(readEntries(operationScorePropertiesStream), readEntries(operationAuthorisationScoreLimitStream));
    }

    /**
     * Constructs an {@link OperationAuthoriser} with the authorisations
     * defined in the provided authorisations property file.
     *
     * @param operationScoreEntries                   operation scores entries
     * @param operationAuthorisationScoreLimitEntries authorisation scores entries
     */
    public OperationChainLimiter(final List<Map.Entry<String, String>> operationScoreEntries,
                                 final List<Map.Entry<String, String>> operationAuthorisationScoreLimitEntries) {
        loadMapsFromEntryLists(operationScoreEntries, operationAuthorisationScoreLimitEntries);
    }

    private static List<Map.Entry<String, String>> readEntries(final Path propFileLocation) {
        List<Map.Entry<String, String>> listEntries;
        if (null != propFileLocation) {
            try {
                listEntries = readEntries(Files.newInputStream(propFileLocation, StandardOpenOption.READ));
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        } else {
            listEntries = new ArrayList<>();
        }

        return listEntries;
    }

    private static List<Map.Entry<String, String>> readEntries(final InputStream stream) {
        final List<Map.Entry<String, String>> listEntries = new ArrayList<>();

        if (null != stream) {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(stream, CommonConstants.UTF_8));

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();

                    if (!line.startsWith("#")) {
                        String[] bits = line.split("=");
                        if (bits.length == 2) {
                            listEntries.add(new AbstractMap.SimpleEntry<>(bits[0], bits[1]));
                        } else if (bits.length != 0) {
                            throw new IllegalArgumentException("Failed to load opScores file : invalid line:%n" + line);
                        }
                    }
                }
            } catch (final IOException e) {
                throw new IllegalArgumentException("Failed to load opScores file : " + e
                        .getMessage(), e);
            } finally {
                CloseableUtil.close(stream);
            }
        }
        return listEntries;
    }

    /**
     * Checks the {@link OperationChain}
     * is allowed to be executed by the user.
     * This is done by checking the user's auths against the auth scores getting the users maximum score limit value.
     * Then checking the operation score of all operations in the chain and comparing the total score value of the chain against a users maximum score limit.
     * If an operation cannot be executed then an {@link IllegalAccessError} is thrown.
     *
     * @param user    the user to authorise.
     * @param opChain the operation chain.
     */
    @Override
    public void preExecute(final OperationChain<?> opChain, final User user) {
        if (null != opChain) {
            Integer chainScore = getChainScore(opChain, user);
            Integer maxAuthScore = getMaxUserAuthScore(user.getOpAuths());
            if (chainScore > maxAuthScore) {
                throw new UnauthorisedException("The maximum score limit for this user is " + maxAuthScore + ".\n" +
                        "The requested operation chain exceeded this score limit.");
            }
        }
    }

    public Integer getChainScore(final OperationChain<?> opChain, final User user) {
        Integer chainScore = 0;

        if (null != opChain) {
            for (final Operation operation : opChain.getOperations()) {
                chainScore += authorise(operation);
            }
        }
        return chainScore;
    }

    /**
     * Iterates through each of the users operation authorisations listed in the config file and returns the highest score
     * associated with those auths.
     * <p>
     * Defaults to 0.
     *
     * @param opAuths a set of operation authorisations
     * @return maxUserScore the highest score associated with any of the supplied user auths
     */
    private Integer getMaxUserAuthScore(final Set<String> opAuths) {
        Integer maxUserScore = 0;
        for (final String opAuth : opAuths) {
            Integer authScore = authScores.get(opAuth);
            if (null != authScore) {
                if (authScore > maxUserScore) {
                    maxUserScore = authScore;
                }
            }
        }
        LOGGER.info("Returning users max operation chain limit score of {}", maxUserScore);
        return maxUserScore;
    }

    @Override
    public <T> T postExecute(final T result, final OperationChain<?> opChain, final User user) {
        // This method can be overridden to add additional authorisation checks on the results.
        return result;
    }

    protected Integer authorise(final Operation operation) {
        if (null != operation) {
            final Class<? extends Operation> opClass = operation.getClass();
            ArrayList<Class<? extends Operation>> keys = new ArrayList<>(operationScores
                    .keySet());
            for (int i = keys.size() - 1; i >= 0; i--) {
                Class<? extends Operation> key = keys.get(i);
                if (key.isAssignableFrom(opClass)) {
                    return operationScores.get(key);
                }
            }
            LOGGER.warn("The operation '{}' was not found in the config file provided the configured default value of {} will be used", operation.getClass().getName(), DEFAULT_OPERATION_SCORE);
        } else {
            LOGGER.warn("A Null operation was passed to the OperationChainLimiter graph hook");
        }
        return DEFAULT_OPERATION_SCORE;
    }

    private void loadMapsFromEntryLists(final List<Map.Entry<String, String>> operationScoreEntries,
                                        final List<Map.Entry<String, String>> operationAuthorisationScoreLimitEntries) {
        Map<Class<? extends Operation>, Integer> opScores = new LinkedHashMap<>();

        for (final Map.Entry<String, String> opScoreEntry : operationScoreEntries) {
            final Class<? extends Operation> opClass;
            String opClassName = opScoreEntry.getKey();
            try {
                opClass = Class.forName(opClassName)
                        .asSubclass(Operation.class);
            } catch (final ClassNotFoundException e) {
                LOGGER.error("An operation class could not be found for operation score property {}", opClassName, e);
                throw new IllegalArgumentException(e);
            }
            final Integer score = Integer.parseInt(opScoreEntry.getValue());
            opScores.put(opClass, score);
        }
        setOpScores(opScores);

        Map<String, Integer> authScores = new HashMap<>();
        for (final Map.Entry<String, String> authScoreEntry : operationAuthorisationScoreLimitEntries) {
            final String authName = authScoreEntry.getKey();
            final Integer score = Integer.parseInt(authScoreEntry.getValue());

            authScores.put(authName, score);
        }
        setAuthScores(authScores);
    }

    /**
     * Set the operation scores.
     *
     * @param opScores a map of operation classes to scores
     */
    public void setOpScores(final Map<Class<? extends Operation>, Integer> opScores) {
        operationScores.putAll(opScores);
    }

    /**
     * Add a score for a given operation class.
     *
     * @param opClass the operation class
     * @param score   the score for the operation class
     */
    public void addOpScore(final Class<? extends Operation> opClass, final Integer score) {
        operationScores.put(opClass, score);
    }

    /**
     * Set the authorisation scores.
     *
     * @param authScores a map of authorisations to scores
     */
    public void setAuthScores(final Map<String, Integer> authScores) {
        this.authScores.putAll(authScores);
    }

    /**
     * Add a score for a given operation class.
     *
     * @param auth  the authorisation
     * @param score the score for the operation class
     */
    public void addAuthScore(final String auth, final Integer score) {
        authScores.put(auth, score);
    }
}
