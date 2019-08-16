package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;
import static net.bytebuddy.agent.builder.AgentBuilder.PoolStrategy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import datadog.trace.bootstrap.WeakMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Custom Pool strategy.
 *
 * <p>Here we are using WeakMap.Provider as the backing ClassLoader -> CacheProvider lookup.
 *
 * <p>We also use our bootstrap proxy when matching against the bootstrap loader.
 *
 * <p>The CacheProvider is a custom implementation that uses guava's cache to expire and limit size.
 *
 * <p>By evicting from the cache we are able to reduce the memory overhead of the agent for apps
 * that have many classes.
 *
 * <p>See eviction policy below.
 */
public class DDCachingPoolStrategy implements PoolStrategy {
  private static final WeakMap<ClassLoader, TypePool.CacheProvider> typePoolCache =
      WeakMap.Provider.newWeakMap();

  @Override
  public TypePool typePool(final ClassFileLocator classFileLocator, final ClassLoader classLoader) {
    final ClassLoader key =
        BOOTSTRAP_CLASSLOADER == classLoader ? Utils.getBootstrapProxy() : classLoader;
    TypePool.CacheProvider cache = typePoolCache.get(key);
    if (null == cache) {
      synchronized (key) {
        cache = typePoolCache.get(key);
        if (null == cache) {
          cache = EvictingCacheProvider.withObjectType(10, TimeUnit.SECONDS);
          typePoolCache.put(key, cache);
        }
      }
    }
    return new TypePool.Default.WithLazyResolution(
        cache, classFileLocator, TypePool.Default.ReaderMode.FAST);
  }

  private static class EvictingCacheProvider implements TypePool.CacheProvider {
    private static final ScheduledThreadPoolExecutor cleanerExecutorService =
        new ScheduledThreadPoolExecutor(
            1,
            new ThreadFactory() {
              @Override
              public Thread newThread(final Runnable r) {
                final Thread thread = new Thread(r, "dd-type-cache-cleaner");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
              }
            });

    static {
      cleanerExecutorService.setRemoveOnCancelPolicy(true);
    }

    /** A map containing all cached resolutions by their names. */
    private final Cache<String, TypePool.Resolution> cache;

    private final ScheduledFuture<?> cancelFuture;

    /** Creates a new simple cache. */
    private EvictingCacheProvider(final long expireDuration, final TimeUnit unit) {
      cache =
          CacheBuilder.newBuilder()
              .initialCapacity(500)
              .maximumSize(10000)
              .expireAfterAccess(expireDuration, unit)
              .build();

      /*
       * The cache only does cleanup on occasional reads and writes.
       * We want to ensure this happens more regularly, so we schedule a thread to do run cleanup manually.
       */
      final long cleanFrequency = expireDuration / 2;
      cancelFuture =
          cleanerExecutorService.scheduleAtFixedRate(
              new CacheCleaner(), cleanFrequency, cleanFrequency, unit);
    }

    private static EvictingCacheProvider withObjectType(
        final long expireDuration, final TimeUnit unit) {
      assert expireDuration % 2 == 0 : "expireDuration must be even";
      final EvictingCacheProvider cacheProvider = new EvictingCacheProvider(expireDuration, unit);
      cacheProvider.register(
          Object.class.getName(), new TypePool.Resolution.Simple(TypeDescription.OBJECT));
      return cacheProvider;
    }

    @Override
    public TypePool.Resolution find(final String name) {
      return cache.getIfPresent(name);
    }

    @Override
    public TypePool.Resolution register(final String name, final TypePool.Resolution resolution) {
      try {
        return cache.get(name, new ResolutionProvider(resolution));
      } catch (final ExecutionException e) {
        return resolution;
      }
    }

    @Override
    public void clear() {
      cache.invalidateAll();
    }

    public long size() {
      return cache.size();
    }

    @Override
    public void finalize() {
      cancelFuture.cancel(true);
    }

    private class CacheCleaner implements Runnable {
      @Override
      public void run() {
        cache.cleanUp();
      }
    }

    private static class ResolutionProvider implements Callable<TypePool.Resolution> {
      private final TypePool.Resolution value;

      private ResolutionProvider(final TypePool.Resolution value) {
        this.value = value;
      }

      @Override
      public TypePool.Resolution call() {
        return value;
      }
    }
  }
}
