/*
 * Copyright 2016-2017 Crown Copyright
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

package uk.gov.gchq.gaffer.named.operation.cache;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.gchq.gaffer.cache.CacheServiceLoader;
import uk.gov.gchq.gaffer.cache.exception.CacheOperationException;
import uk.gov.gchq.gaffer.commonutil.iterable.CloseableIterable;
import uk.gov.gchq.gaffer.commonutil.iterable.WrappedCloseableIterable;
import uk.gov.gchq.gaffer.named.operation.NamedOperationDetail;
import uk.gov.gchq.gaffer.user.User;

import java.util.HashSet;
import java.util.Set;

public class NamedOperationGafferCache extends AbstractNamedOperationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamedOperationGafferCache.class);
    private static final String CACHE_NAME = "NamedOperation";


    @Override
    public CloseableIterable<NamedOperationDetail> getAllNamedOperations(final User user) {
        Set<String> keys = CacheServiceLoader.getService().getAllKeysFromCache(CACHE_NAME);
        Set<NamedOperationDetail> executables = new HashSet<>();
        for (final String key : keys) {
            try {
                NamedOperationDetail op = getFromCache(key);
                if (op.hasReadAccess(user)) {
                    executables.add(op);
                }
            } catch (CacheOperationFailedException e) {
                LOGGER.error(e.getMessage(), e);
            }

        }
        return new WrappedCloseableIterable<>(executables);
    }

    @Override
    public void clear() throws CacheOperationFailedException {
        try {
            CacheServiceLoader.getService().clearCache(CACHE_NAME);
        } catch (CacheOperationException e) {
            throw new CacheOperationFailedException("Failed to clear cache", e);
        }
    }

    @Override
    public void deleteFromCache(final String name) throws CacheOperationFailedException {
        CacheServiceLoader.getService().removeFromCache(CACHE_NAME, name);

        if (CacheServiceLoader.getService().getFromCache(CACHE_NAME, name) != null) {
            throw new CacheOperationFailedException("Failed to remove " + name + " from cache");
        }
    }

    @Override
    public void addToCache(final String name, final NamedOperationDetail operation, final boolean overwrite) throws CacheOperationFailedException {
        try {
            if (overwrite) {
                CacheServiceLoader.getService().putInCache(CACHE_NAME, name, operation);
            } else {
                CacheServiceLoader.getService().putSafeInCache(CACHE_NAME, name, operation);
            }
        } catch (CacheOperationException e) {
            throw new CacheOperationFailedException(e);
        }
    }

    @Override
    public NamedOperationDetail getFromCache(final String name) throws CacheOperationFailedException {
        if (name == null) {
            throw new CacheOperationFailedException("Operation name cannot be null");
        }
        NamedOperationDetail op = CacheServiceLoader.getService().getFromCache(CACHE_NAME, name);

        if (op != null) {
            return op;
        }
        throw new CacheOperationFailedException("No named operation with the name " + name + " exists in the cache");
    }
}
