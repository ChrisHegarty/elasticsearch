/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices.analysis;

import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.NamedRegistry;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.DeprecationCategory;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.annotations.SettingsProxy;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.analysis.AnalyzerProvider;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.HunspellTokenFilterFactory;
import org.elasticsearch.index.analysis.KeywordAnalyzerProvider;
import org.elasticsearch.index.analysis.LowercaseNormalizerProvider;
import org.elasticsearch.index.analysis.PreBuiltAnalyzerProviderFactory;
import org.elasticsearch.index.analysis.PreConfiguredCharFilter;
import org.elasticsearch.index.analysis.PreConfiguredTokenFilter;
import org.elasticsearch.index.analysis.PreConfiguredTokenizer;
import org.elasticsearch.index.analysis.ShingleTokenFilterFactory;
import org.elasticsearch.index.analysis.SimpleAnalyzerProvider;
import org.elasticsearch.index.analysis.StandardAnalyzerProvider;
import org.elasticsearch.index.analysis.StandardTokenizerFactory;
import org.elasticsearch.index.analysis.StopAnalyzerProvider;
import org.elasticsearch.index.analysis.StopTokenFilterFactory;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.WhitespaceAnalyzerProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.sp.api.analysis.settings.AnalysisSettings;
import org.elasticsearch.sp.api.analysis.settings.ClusterSettings;
import org.elasticsearch.sp.api.analysis.settings.NodeSettings;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.unmodifiableMap;
import static org.elasticsearch.plugins.AnalysisPlugin.requiresAnalysisSettings;

/**
 * Sets up {@link AnalysisRegistry}.
 */
public final class AnalysisModule {
    static {
        Settings build = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metadata = IndexMetadata.builder("_na_").settings(build).build();
        NA_INDEX_SETTINGS = new IndexSettings(metadata, Settings.EMPTY);
    }

    private static final IndexSettings NA_INDEX_SETTINGS;
    private static final DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(AnalysisModule.class);

    private final HunspellService hunspellService;
    private final AnalysisRegistry analysisRegistry;
    private PluginsService pluginsService;

    public AnalysisModule(Environment environment, List<AnalysisPlugin> plugins, PluginsService pluginsService) throws IOException {
        this.pluginsService = pluginsService;
        NamedRegistry<AnalysisProvider<CharFilterFactory>> charFilters = setupCharFilters(plugins);
        NamedRegistry<org.apache.lucene.analysis.hunspell.Dictionary> hunspellDictionaries = setupHunspellDictionaries(plugins);
        hunspellService = new HunspellService(environment.settings(), environment, hunspellDictionaries.getRegistry());
        NamedRegistry<AnalysisProvider<TokenFilterFactory>> tokenFilters = setupTokenFilters(plugins, hunspellService, pluginsService);
        NamedRegistry<AnalysisProvider<TokenizerFactory>> tokenizers = setupTokenizers(plugins);
        NamedRegistry<AnalysisProvider<AnalyzerProvider<?>>> analyzers = setupAnalyzers(plugins);
        NamedRegistry<AnalysisProvider<AnalyzerProvider<?>>> normalizers = setupNormalizers(plugins);

        Map<String, PreConfiguredCharFilter> preConfiguredCharFilters = setupPreConfiguredCharFilters(plugins);
        Map<String, PreConfiguredTokenFilter> preConfiguredTokenFilters = setupPreConfiguredTokenFilters(plugins);
        Map<String, PreConfiguredTokenizer> preConfiguredTokenizers = setupPreConfiguredTokenizers(plugins);
        Map<String, PreBuiltAnalyzerProviderFactory> preConfiguredAnalyzers = setupPreBuiltAnalyzerProviderFactories(plugins);

        analysisRegistry = new AnalysisRegistry(
            environment,
            charFilters.getRegistry(),
            tokenFilters.getRegistry(),
            tokenizers.getRegistry(),
            analyzers.getRegistry(),
            normalizers.getRegistry(),
            preConfiguredCharFilters,
            preConfiguredTokenFilters,
            preConfiguredTokenizers,
            preConfiguredAnalyzers
        );
    }

    HunspellService getHunspellService() {
        return hunspellService;
    }

    public AnalysisRegistry getAnalysisRegistry() {
        return analysisRegistry;
    }

    private static NamedRegistry<AnalysisProvider<CharFilterFactory>> setupCharFilters(List<AnalysisPlugin> plugins) {
        NamedRegistry<AnalysisProvider<CharFilterFactory>> charFilters = new NamedRegistry<>("char_filter");
        charFilters.extractAndRegister(plugins, AnalysisPlugin::getCharFilters);
        return charFilters;
    }

    public static NamedRegistry<org.apache.lucene.analysis.hunspell.Dictionary> setupHunspellDictionaries(List<AnalysisPlugin> plugins) {
        NamedRegistry<org.apache.lucene.analysis.hunspell.Dictionary> hunspellDictionaries = new NamedRegistry<>("dictionary");
        hunspellDictionaries.extractAndRegister(plugins, AnalysisPlugin::getHunspellDictionaries);
        return hunspellDictionaries;
    }

    private static NamedRegistry<AnalysisProvider<TokenFilterFactory>> setupTokenFilters(
        List<AnalysisPlugin> plugins,
        HunspellService hunspellService,
        PluginsService pluginsService) {
        NamedRegistry<AnalysisProvider<TokenFilterFactory>> tokenFilters = new NamedRegistry<>("token_filter");
        tokenFilters.register("stop", StopTokenFilterFactory::new);
        // Add "standard" for old indices (bwc)
        tokenFilters.register("standard", new AnalysisProvider<TokenFilterFactory>() {
            @Override
            public TokenFilterFactory get(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
                if (indexSettings.getIndexVersionCreated().before(Version.V_7_0_0)) {
                    deprecationLogger.warn(
                        DeprecationCategory.ANALYSIS,
                        "standard_deprecation",
                        "The [standard] token filter name is deprecated and will be removed in a future version."
                    );
                } else {
                    throw new IllegalArgumentException("The [standard] token filter has been removed.");
                }
                return new AbstractTokenFilterFactory(name, settings) {
                    @Override
                    public TokenStream create(TokenStream tokenStream) {
                        return tokenStream;
                    }
                };
            }

            @Override
            public boolean requiresAnalysisSettings() {
                return false;
            }
        });
        tokenFilters.register("shingle", ShingleTokenFilterFactory::new);
        tokenFilters.register(
            "hunspell",
            requiresAnalysisSettings(
                (indexSettings, env, name, settings) -> new HunspellTokenFilterFactory(indexSettings, name, settings, hunspellService)
            )
        );

        // Register Stable Plugins
        if (pluginsService != null) {
            pluginsService.loadServiceProviders(org.elasticsearch.sp.api.analysis.AnalysisPlugin.class)
                .stream()
                .map(org.elasticsearch.sp.api.analysis.AnalysisPlugin::getTokenFilterFactories)
                .map(AnalysisModule::getStringAnalysisProviderMap)
                .forEach(tokenFilters::register);
        }

        tokenFilters.extractAndRegister(plugins, AnalysisPlugin::getTokenFilters);
        return tokenFilters;
    }

    private static Map<String, AnalysisProvider<TokenFilterFactory>>
    getStringAnalysisProviderMap(Map<String, Class<? extends org.elasticsearch.sp.api.analysis.TokenFilterFactory>> tokenFilterFactories) {
        Map<String, AnalysisProvider<TokenFilterFactory>> res = new HashMap<>();
        for (var entry : tokenFilterFactories.entrySet()) {
            String name = entry.getKey();
            var cls = entry.getValue();
            // TokenFilterFactory
            var mh = findStableAnalysisPluginFactoryCtr(cls);
            var noArgsCtrHandle = mh.asType(mh.type().changeReturnType(org.elasticsearch.sp.api.analysis.TokenFilterFactory.class));

            res.put(name, new AnalysisProvider<TokenFilterFactory>() {
                @Override
                public TokenFilterFactory get(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
                    // tokenFilterFactory = createInstance(cls, indexSettings, environment.settings(), settings);
                    try {
                        var tokenFilterFactory = (org.elasticsearch.sp.api.analysis.TokenFilterFactory) noArgsCtrHandle.invokeExact();
                        return new TokenFilterFactory() {
                            @Override
                            public String name() {
                                return tokenFilterFactory.name();
                            }

                            @Override
                            public TokenStream create(TokenStream tokenStream) {
                                return tokenFilterFactory.create(tokenStream);
                            }
                        };
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                }
            });
        }

        return res;
    }

    // Currently, finds the no-args constructor.
    private static MethodHandle findStableAnalysisPluginFactoryCtr(Class<?> cls) {
        try {
            var lookup = MethodHandles.publicLookup();
            return lookup.findConstructor(cls, MethodType.methodType(void.class));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static org.elasticsearch.sp.api.analysis.TokenFilterFactory createInstance(
        Class<? extends org.elasticsearch.sp.api.analysis.TokenFilterFactory> cls,
        IndexSettings indexSettings, Settings nodeSettings, Settings analysisSettings) {
        try {
            for (Constructor<?> constructor : cls.getConstructors()) {
                org.elasticsearch.sp.api.analysis.settings.Inject inject =
                    constructor.getAnnotation(org.elasticsearch.sp.api.analysis.settings.Inject.class);
                if (inject != null) {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    Object[] parameters = new Object[parameterTypes.length];
                    for (int i =0; i< parameterTypes.length; i++) {
                        Object settings = createSettings(parameterTypes[i], indexSettings, nodeSettings, analysisSettings);
                        parameters[i] = settings;
                    }
                    return (org.elasticsearch.sp.api.analysis.TokenFilterFactory) constructor.newInstance(parameters);
                }
            }

        } catch (Exception e) {
            // e.printStackTrace();
        }
        throw new RuntimeException("cannot create instance of " + cls + ", no injectable ctr found");
    }

    private static <T> T createSettings(Class<T> settingsClass, IndexSettings indexSettings, Settings nodeSettings, Settings analysisSettings) {
        if( settingsClass.getAnnotationsByType(NodeSettings.class).length >0 ) {
            return SettingsProxy.create(nodeSettings, settingsClass);
        }
        if(settingsClass.getAnnotationsByType(AnalysisSettings.class) .length >0) {
            return  SettingsProxy.create(analysisSettings, settingsClass);

        }
        if(settingsClass.getAnnotationsByType(ClusterSettings.class).length >0) {
            return null;//SettingsProxy.create(clusterService, settingsClass);
        }
        if(settingsClass.getAnnotationsByType(org.elasticsearch.sp.api.analysis.settings.IndexSettings.class).length >0) {
            return   SettingsProxy.create(indexSettings, settingsClass);
        }

         throw new IllegalArgumentException("unsupported parameter");
    }

    static Map<String, PreBuiltAnalyzerProviderFactory> setupPreBuiltAnalyzerProviderFactories(List<AnalysisPlugin> plugins) {
        NamedRegistry<PreBuiltAnalyzerProviderFactory> preConfiguredCharFilters = new NamedRegistry<>("pre-built analyzer");
        for (AnalysisPlugin plugin : plugins) {
            for (PreBuiltAnalyzerProviderFactory factory : plugin.getPreBuiltAnalyzerProviderFactories()) {
                preConfiguredCharFilters.register(factory.getName(), factory);
            }
        }
        return unmodifiableMap(preConfiguredCharFilters.getRegistry());
    }

    static Map<String, PreConfiguredCharFilter> setupPreConfiguredCharFilters(List<AnalysisPlugin> plugins) {
        NamedRegistry<PreConfiguredCharFilter> preConfiguredCharFilters = new NamedRegistry<>("pre-configured char_filter");

        // No char filter are available in lucene-core so none are built in to Elasticsearch core

        for (AnalysisPlugin plugin : plugins) {
            for (PreConfiguredCharFilter filter : plugin.getPreConfiguredCharFilters()) {
                preConfiguredCharFilters.register(filter.getName(), filter);
            }
        }
        return unmodifiableMap(preConfiguredCharFilters.getRegistry());
    }

    static Map<String, PreConfiguredTokenFilter> setupPreConfiguredTokenFilters(List<AnalysisPlugin> plugins) {
        NamedRegistry<PreConfiguredTokenFilter> preConfiguredTokenFilters = new NamedRegistry<>("pre-configured token_filter");

        // Add filters available in lucene-core
        preConfiguredTokenFilters.register("lowercase", PreConfiguredTokenFilter.singleton("lowercase", true, LowerCaseFilter::new));
        // Add "standard" for old indices (bwc)
        preConfiguredTokenFilters.register(
            "standard",
            PreConfiguredTokenFilter.elasticsearchVersion("standard", true, (reader, version) -> {
                // This was originally removed in 7_0_0 but due to a cacheing bug it was still possible
                // in certain circumstances to create a new index referencing the standard token filter
                // until version 7_5_2
                if (version.before(Version.V_7_6_0)) {
                    deprecationLogger.warn(
                        DeprecationCategory.ANALYSIS,
                        "standard_deprecation",
                        "The [standard] token filter is deprecated and will be removed in a future version."
                    );
                } else {
                    throw new IllegalArgumentException("The [standard] token filter has been removed.");
                }
                return reader;
            })
        );
        /* Note that "stop" is available in lucene-core but it's pre-built
         * version uses a set of English stop words that are in
         * lucene-analyzers-common so "stop" is defined in the analysis-common
         * module. */

        for (AnalysisPlugin plugin : plugins) {
            for (PreConfiguredTokenFilter filter : plugin.getPreConfiguredTokenFilters()) {
                preConfiguredTokenFilters.register(filter.getName(), filter);
            }
        }
        return unmodifiableMap(preConfiguredTokenFilters.getRegistry());
    }

    static Map<String, PreConfiguredTokenizer> setupPreConfiguredTokenizers(List<AnalysisPlugin> plugins) {
        NamedRegistry<PreConfiguredTokenizer> preConfiguredTokenizers = new NamedRegistry<>("pre-configured tokenizer");

        // Temporary shim to register old style pre-configured tokenizers
        for (PreBuiltTokenizers tokenizer : PreBuiltTokenizers.values()) {
            String name = tokenizer.name().toLowerCase(Locale.ROOT);
            PreConfiguredTokenizer preConfigured = switch (tokenizer.getCachingStrategy()) {
                case ONE -> PreConfiguredTokenizer.singleton(name, () -> tokenizer.create(Version.CURRENT));
                default -> throw new UnsupportedOperationException("Caching strategy unsupported by temporary shim [" + tokenizer + "]");
            };
            preConfiguredTokenizers.register(name, preConfigured);
        }
        for (AnalysisPlugin plugin : plugins) {
            for (PreConfiguredTokenizer tokenizer : plugin.getPreConfiguredTokenizers()) {
                preConfiguredTokenizers.register(tokenizer.getName(), tokenizer);
            }
        }

        return unmodifiableMap(preConfiguredTokenizers.getRegistry());
    }

    private static NamedRegistry<AnalysisProvider<TokenizerFactory>> setupTokenizers(List<AnalysisPlugin> plugins) {
        NamedRegistry<AnalysisProvider<TokenizerFactory>> tokenizers = new NamedRegistry<>("tokenizer");
        tokenizers.register("standard", StandardTokenizerFactory::new);

        /*ServiceLoader<org.elasticsearch.sp.api.analysis.AnalysisPlugin> load =
            ServiceLoader.load(org.elasticsearch.sp.api.analysis.AnalysisPlugin.class);
        for (org.elasticsearch.sp.api.analysis.AnalysisPlugin module : load) {
            Map<String, Class<? extends org.elasticsearch.sp.api.analysis.TokenizerFactory>> tokenizerFactories =
                module.getTokenizerFactories();

            *//*need to provide plugin's class loader otherwise no results??*//*
            // Optional<ServiceLoader.Provider<TokenFilterFactoryProvider>> first = load.stream().findFirst();

            Map<String,AnalysisProvider<TokenizerFactory>> collect = emptyMap(); *//*= tokenizerFactories.entrySet()
                .stream().map(AnalysisModule::mapTokenizersToOldApi)
                .collect(Collectors.toMap());*//*
            tokenizers.register(collect);
        }*/


        tokenizers.extractAndRegister(plugins, AnalysisPlugin::getTokenizers);
        return tokenizers;
    }


    private static NamedRegistry<AnalysisProvider<AnalyzerProvider<?>>> setupAnalyzers(List<AnalysisPlugin> plugins) {
        NamedRegistry<AnalysisProvider<AnalyzerProvider<?>>> analyzers = new NamedRegistry<>("analyzer");
        analyzers.register("default", StandardAnalyzerProvider::new);
        analyzers.register("standard", StandardAnalyzerProvider::new);
        analyzers.register("simple", SimpleAnalyzerProvider::new);
        analyzers.register("stop", StopAnalyzerProvider::new);
        analyzers.register("whitespace", WhitespaceAnalyzerProvider::new);
        analyzers.register("keyword", KeywordAnalyzerProvider::new);
        analyzers.extractAndRegister(plugins, AnalysisPlugin::getAnalyzers);
        return analyzers;
    }

    private static NamedRegistry<AnalysisProvider<AnalyzerProvider<?>>> setupNormalizers(List<AnalysisPlugin> plugins) {
        NamedRegistry<AnalysisProvider<AnalyzerProvider<?>>> normalizers = new NamedRegistry<>("normalizer");
        normalizers.register("lowercase", LowercaseNormalizerProvider::new);
        // TODO: pluggability?
        return normalizers;
    }

    /**
     * The basic factory interface for analysis components.
     */
    public interface AnalysisProvider<T> {

        /**
         * Creates a new analysis provider.
         *
         * @param indexSettings the index settings for the index this provider is created for
         * @param environment   the nodes environment to load resources from persistent storage
         * @param name          the name of the analysis component
         * @param settings      the component specific settings without context prefixes
         * @return a new provider instance
         * @throws IOException if an {@link IOException} occurs
         */
        T get(IndexSettings indexSettings, Environment environment, String name, Settings settings) throws IOException;

        /**
         * Creates a new global scope analysis provider without index specific settings not settings for the provider itself.
         * This can be used to get a default instance of an analysis factory without binding to an index.
         *
         * @param environment the nodes environment to load resources from persistent storage
         * @param name        the name of the analysis component
         * @return a new provider instance
         * @throws IOException              if an {@link IOException} occurs
         * @throws IllegalArgumentException if the provider requires analysis settings ie. if {@link #requiresAnalysisSettings()} returns
         *                                  <code>true</code>
         */
        default T get(Environment environment, String name) throws IOException {
            if (requiresAnalysisSettings()) {
                throw new IllegalArgumentException("Analysis settings required - can't instantiate analysis factory");
            }
            return get(NA_INDEX_SETTINGS, environment, name, NA_INDEX_SETTINGS.getSettings());
        }

        /**
         * If <code>true</code> the analysis component created by this provider requires certain settings to be instantiated.
         * it can't be created with defaults. The default is <code>false</code>.
         */
        default boolean requiresAnalysisSettings() {
            return false;
        }
    }
}
