package com.connexta.osgeyes;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.ArtifactInfoGroup;
import org.apache.maven.index.ArtifactScanningListener;
import org.apache.maven.index.DefaultScannerListener;
import org.apache.maven.index.Field;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.GroupedSearchRequest;
import org.apache.maven.index.GroupedSearchResponse;
import org.apache.maven.index.Grouping;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IndexerEngine;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.Scanner;
import org.apache.maven.index.ScanningRequest;
import org.apache.maven.index.ScanningResult;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.expr.UserInputSearchExpression;
import org.apache.maven.index.search.grouping.GAGrouping;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionScheme;

public class IndexingExample implements Callable<Void>, Closeable {

  private static final String PROP_WORKING_DIR = System.getProperty("user.dir");

  private static final String PROP_USER_HOME = System.getProperty("user.home");

  private static final String PROP_USER_REPO = System.getProperty("user.repo");

  private static final String INDEX_DIR_NAME = ".index";

  private static final String MIN_INDEX_CREATOR_ID = "min";

  private static final String OSGI_INDEX_CREATOR_ID = "osgi-metadatas";

  private final PlexusContainer plexusContainer;

  private final Indexer indexer;

  private final IndexerEngine indexerEngine;

  private final Scanner repositoryScanner;

  private final BufferedReader consoleIn;

  private final Criteria criteria;

  public static void main(String[] args) throws Exception {
    try (final IndexingExample app = new IndexingExample()) {
      app.call();
    }
  }

  @Override
  public void close() throws IOException {
    consoleIn.close();
  }

  public IndexingExample() throws PlexusContainerException, ComponentLookupException {
    // Create a Plexus container, the Maven default IoC container
    // Note that maven-indexer is a Plexus component
    final DefaultContainerConfiguration config = new DefaultContainerConfiguration();
    config.setClassPathScanning(PlexusConstants.SCANNING_INDEX);

    this.plexusContainer = new DefaultPlexusContainer(config);
    this.indexer = plexusContainer.lookup(Indexer.class);
    this.indexerEngine = plexusContainer.lookup(IndexerEngine.class);
    this.repositoryScanner = plexusContainer.lookup(Scanner.class);
    this.consoleIn = new BufferedReader(new InputStreamReader(System.in));

    this.criteria = new Criteria(indexer);

    plexusContainer.addComponent(
        new MvnHierarchyIndexCreator(), IndexCreator.class, MvnHierarchyIndexCreator.ID);
  }

  @Nullable
  private static Path getUserSpecifiedRepoLocation() {
    if (PROP_USER_REPO == null) {
      return null;
    }

    final Path userRepo = Paths.get(PROP_USER_REPO);
    if (userRepo.isAbsolute()) {
      final File userRepoFile = userRepo.toFile();
      if (userRepoFile.exists() && userRepoFile.isDirectory()) {
        return userRepo;
      }
    } else {
      final Path workingDir = Paths.get(PROP_WORKING_DIR);
      final Path userRepoResolved = workingDir.resolve(userRepo);
      final File userRepoResolvedFile = userRepoResolved.toFile();
      if (userRepoResolvedFile.exists() && userRepoResolvedFile.isDirectory()) {
        return userRepoResolved;
      }
    }

    return null;
  }

  private static Path getRepoLocation() {
    // Set by the JVM - should not be null
    assert PROP_WORKING_DIR != null && !PROP_WORKING_DIR.isEmpty();
    assert PROP_USER_HOME != null && !PROP_USER_HOME.isEmpty();

    final Path userRepo = getUserSpecifiedRepoLocation();
    if (userRepo != null) {
      return userRepo;
    }

    final Path defaultMavenM2 = Paths.get(PROP_USER_HOME).resolve(".m2").resolve("repository");
    final File defaultMavenM2File = defaultMavenM2.toFile();
    if (defaultMavenM2File.exists() && defaultMavenM2File.isDirectory()) {
      return defaultMavenM2;
    }

    String message =
        "No valid indexing target could be acquired"
            + System.lineSeparator()
            + "  JVM working directory: "
            + PROP_WORKING_DIR
            + System.lineSeparator()
            + "  User home directory: "
            + PROP_USER_HOME
            + System.lineSeparator()
            + "  User repo directory: "
            + PROP_USER_REPO;

    throw new IllegalStateException(message);
  }

  @Override
  public Void call() throws Exception {
    logline("------------------------------------------------------------------------------------");
    logline("OSG-Eyes Maven Indexer");
    logline("------------------------------------------------------------------------------------");

    final Path repoLocation = getRepoLocation();

    logline("JVM working directory: " + PROP_WORKING_DIR);
    logline("User home directory: " + PROP_USER_HOME);
    logline("User repo directory: " + repoLocation);

    logline("Registered index creators:");
    plexusContainer
        .getComponentDescriptorList(IndexCreator.class, null)
        .forEach(cd -> logline("  " + cd.getImplementation()));

    IndexingContext indexingContext = null;
    try {
      indexingContext = indexTryCreate(repoLocation);
      // Will revisit incremental updates later
      // remoteIndexUpdate(indexingContext);
      waitForUserToContinue();

      // No need to list artifacts right now
      // listAllArtifacts(indexingContext);
      // waitForUserToContinue();

      //      search(
      //          indexingContext,
      //          VersionRangeFilter.atMinimum("2.19.0").butStrictlyLessThan("2.20.0"),
      //          Criteria.of(MAVEN.GROUP_ID, "ddf"),
      //          Criteria.of(MAVEN.ARTIFACT_ID, "ddf"),
      //          Criteria.of(MAVEN.PACKAGING, "pom"));
      //      waitForUserToContinue();
      //
      //      search(
      //          indexingContext,
      //          Criteria.of(MAVEN.GROUP_ID, "ddf"),
      //          Criteria.of(MAVEN.ARTIFACT_ID, "ddf"),
      //          Criteria.of(MAVEN.PACKAGING, "pom"),
      //          Criteria.of(MAVEN.VERSION, "2.19.5"));
      //      waitForUserToContinue();

      QueryCriteria packagingIsPomOrBundle =
          criteria.of(
              criteria.of(MAVEN.PACKAGING, "pom", criteria.options().with(Occur.SHOULD)),
              criteria.of(MAVEN.PACKAGING, "bundle", criteria.options().with(Occur.SHOULD)));

      search(
          indexingContext,
          // Criteria.of(MAVEN.VERSION, "2.19.5"),
          // Criteria.of(MvnOntology.POM_MODULES, "catalog"),
          criteria.of(
              criteria.of(MAVEN.ARTIFACT_ID, "catalog"),
              criteria.of(MvnOntology.POM_PARENT, "mvn:ddf/ddf/2.19.5"),
              packagingIsPomOrBundle));
      waitForUserToContinue();

      search(
          indexingContext,
          criteria.of(
              criteria.of(MAVEN.ARTIFACT_ID, "transformer"),
              criteria.of(MvnOntology.POM_PARENT, "mvn:ddf.catalog/catalog/2.19.5"),
              packagingIsPomOrBundle));
      waitForUserToContinue();

      search(
          indexingContext,
          criteria.of(
              criteria.of(MAVEN.ARTIFACT_ID, "catalog-transformer-html"),
              criteria.of(MvnOntology.POM_PARENT, "mvn:ddf.catalog.transformer/transformer/2.19.5"),
              packagingIsPomOrBundle));
      waitForUserToContinue();

      // OSGI Attributes are using an unsupported indexing model
      // --
      // search(indexingContext, Criteria.of(OSGI.IMPORT_PACKAGE, "ddf.catalog.validation*"));
      // waitForUserToContinue();

      // Sample grouped search
      // --
      // searchGroupedMavenPlugins(indexingContext);
      // waitForUserToContinue();

    } finally {
      if (indexingContext != null) {
        logline("Closing indexing context...");
        indexer.closeIndexingContext(indexingContext, false);
        logline("...done!");
      }
    }

    logline("Shutting down");
    return null;
  }

  private void searchGroupedMavenPlugins(IndexingContext indexingContext) throws IOException {
    searchGrouped(
        indexingContext,
        new GAGrouping(),
        criteria.of(
            criteria.of(MAVEN.GROUP_ID, "org.apache.maven.plugins"),
            criteria.of(MAVEN.PACKAGING, "maven-plugin")));
    waitForUserToContinue();
  }

  private void searchMoreGuava(IndexingContext indexingContext)
      throws InvalidVersionSpecificationException, IOException {

    // CASES TO VERIFY
    // - VersionRangeFilter cases (max, min, and variations on bounds, inclusive/exclusive)
    // - Criteria.of(MAVEN.PACKAGING, "jar")
    // - Criteria.of(MAVEN.CLASSIFIER, Field.NOT_PRESENT).with(Occur.MUST_NOT)
    // - Criteria.of(MAVEN.CLASSIFIER, "*").with(Occur.MUST_NOT)
    // ETC
    // - How grouped searches behave
    // - Differences between iterator and flat searches

    search(
        indexingContext,
        criteria.of(
            criteria.of(MAVEN.GROUP_ID, "com.google.guava"),
            criteria.of(MAVEN.ARTIFACT_ID, "guava"),
            criteria.of(MAVEN.PACKAGING, "bundle"),
            criteria.of(MAVEN.CLASSIFIER, "*", criteria.options().with(Occur.MUST_NOT))));

    search(
        indexingContext,
        criteria.of(
            criteria.of(MAVEN.GROUP_ID, "com.google.guava"),
            criteria.of(MAVEN.ARTIFACT_ID, "guava"),
            criteria.of(MAVEN.PACKAGING, "bundle"),
            criteria.of(MAVEN.CLASSIFIER, "*", criteria.options().with(Occur.MUST_NOT))));

    // Expected: [20.0, 27.0.1]
    search(
        indexingContext,
        VersionRangeFilter.atMinimum("20.0"),
        criteria.of(
            criteria.of(MAVEN.GROUP_ID, "com.google.guava"),
            criteria.of(MAVEN.ARTIFACT_ID, "guava")));

    // Expected: [14.0.1, 19.0]
    search(
        indexingContext,
        VersionRangeFilter.atMinimum("14.0").butStrictlyLessThan("20.0"),
        criteria.of(
            criteria.of(MAVEN.GROUP_ID, "com.google.guava"),
            criteria.of(MAVEN.ARTIFACT_ID, "guava")));

    // Expected: [19.0, 20.0]
    search(
        indexingContext,
        VersionRangeFilter.atMaximum("20.0").butStrictlyGreaterThan("14.0.1"),
        criteria.of(
            criteria.of(MAVEN.GROUP_ID, "com.google.guava"),
            criteria.of(MAVEN.ARTIFACT_ID, "guava")));

    search(
        indexingContext,
        criteria.of(MAVEN.SHA1, "89507701249388", criteria.options().partialInput()));

    waitForUserToContinue();
  }

  private IndexingContext indexTryCreate(Path repoLocation)
      throws IOException, ComponentLookupException {
    final Path indexLocation = repoLocation.resolve(INDEX_DIR_NAME);
    final File indexLocationDir = indexLocation.toFile();
    final String[] indexDirContents = indexLocationDir.list();

    final File repoLocationDir = repoLocation.toFile();
    final List<IndexCreator> indexers = new ArrayList<>();

    indexers.add(plexusContainer.lookup(IndexCreator.class, MIN_INDEX_CREATOR_ID));
    indexers.add(plexusContainer.lookup(IndexCreator.class, OSGI_INDEX_CREATOR_ID));
    indexers.add(plexusContainer.lookup(IndexCreator.class, MvnHierarchyIndexCreator.ID));

    final Supplier<IndexingContext> contextSupplier =
        () -> {
          try {
            return indexer.createIndexingContext(
                "localhost-osgeyes",
                "localhost",
                repoLocationDir,
                indexLocationDir,
                // Could supply repoUrl here if you wanted to proxy
                // "http://localhost:8000/"
                null,
                null,
                true,
                true,
                indexers);
          } catch (ExistingLuceneIndexMismatchException em) {
            throw new IllegalStateException(em);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };

    // Crude way to account for '.DS_Store' by checking for > 1 instead of > 0
    if (indexLocationDir.exists()
        && indexLocationDir.isDirectory()
        && indexDirContents != null
        && indexDirContents.length > 1) {
      logline("Index found: " + indexLocationDir);
      return contextSupplier.get();
    }

    if (indexLocationDir.exists()) {
      if (indexLocationDir.isDirectory()) {
        try (final Stream<Path> paths = Files.walk(indexLocation)) {
          paths
              .sorted(Comparator.reverseOrder())
              .forEach(
                  f -> {
                    try {
                      Files.delete(f);
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
        }
      } else {
        Files.delete(indexLocation);
      }
    }

    Files.createDirectories(indexLocation);

    final IndexingContext indexingContext = contextSupplier.get();
    logline("Creating index for repository at " + indexingContext.getRepository());
    logline("Creating index at " + indexLocationDir);

    final ArtifactScanningListener listener =
        new DefaultScannerListener(indexingContext, indexerEngine, false, null);
    final ScanningRequest scanningRequest = new ScanningRequest(indexingContext, listener);
    final ScanningResult result = repositoryScanner.scan(scanningRequest);

    logline("Scan has finished");
    logline("Total files: " + result.getTotalFiles());
    logline("Total deleted: " + result.getDeletedFiles());

    if (!result.getExceptions().isEmpty()) {
      logline("Some problems occurred during the scan:");
      result.getExceptions().forEach(Exception::printStackTrace);
    }

    return indexingContext;
  }

  private void search(
      IndexingContext indexingContext, ArtifactInfoFilter filter, QueryCriteria criteria)
      throws IOException {
    final Query query = criteria.getQuery();
    logline("Searching for " + criteria.toString());

    final IteratorSearchResponse response =
        indexer.searchIterator(
            new IteratorSearchRequest(query, Collections.singletonList(indexingContext), filter));

    for (ArtifactInfo artifact : response.getResults()) {
      logline(artifact.toString());
      artifact.getAttributes().forEach((k, v) -> logline("  [ " + k + " " + v + " ]"));
    }

    logline("------------");
    logline("Total: " + response.getTotalHitsCount());
    logline();
  }

  private void search(IndexingContext indexingContext, QueryCriteria criteria) throws IOException {
    final Query query = criteria.getQuery();
    logline("Searching for " + criteria.toString());
    search(indexingContext, query);
  }

  private void search(IndexingContext indexingContext, Query query) throws IOException {
    final FlatSearchResponse response =
        indexer.searchFlat(new FlatSearchRequest(query, indexingContext));

    for (ArtifactInfo artifact : response.getResults()) {
      logline(artifact.toString());
      artifact.getAttributes().forEach((k, v) -> logline("  [ " + k + " " + v + " ]"));
    }

    logline("------------");
    logline("Total: " + response.getTotalHitsCount());
    logline();
  }

  private void searchGrouped(
      IndexingContext indexingContext, Grouping grouping, QueryCriteria criteria)
      throws IOException {
    final int maxArtifactDescriptionStringWidth = 60;
    final Query query = criteria.getQuery();
    logline("Searching (grouped) for " + criteria.toString());

    final GroupedSearchResponse response =
        indexer.searchGrouped(new GroupedSearchRequest(query, grouping, indexingContext));

    for (Map.Entry<String, ArtifactInfoGroup> entry : response.getResults().entrySet()) {
      final ArtifactInfo artifact = entry.getValue().getArtifactInfos().iterator().next();
      logline("Entry: " + artifact);
      logline("Latest version:  " + artifact.getVersion());
      logline(
          StringUtils.isBlank(artifact.getDescription())
              ? "No description in plugin's POM."
              : StringUtils.abbreviate(
                  artifact.getDescription(), maxArtifactDescriptionStringWidth));
      artifact.getAttributes().forEach((k, v) -> logline("  [ " + k + " " + v + " ]"));
      logline();
    }

    logline("------------");
    logline("Total record hits: " + response.getTotalHitsCount());
    logline();
  }

  private static class Criteria {

    private final CriteriaOptions options;

    // Goal is to eventually remove this as an inverted dependency
    private final Indexer indexer;

    public Criteria(Indexer indexer) {
      this(new CriteriaOptions(), indexer);
    }

    public Criteria(CriteriaOptions options, Indexer indexer) {
      this.options = Objects.requireNonNull(options, "options cannot be null");
      this.indexer = Objects.requireNonNull(indexer, "indexer cannot be null");
    }

    public CriteriaOptions getOptions() {
      return options;
    }

    public Indexer getIndexer() {
      return indexer;
    }

    public QueryCriteria of(Field field, String value) {
      return new KeyValueCriteria(field, value, new CriteriaOptions(), indexer);
    }

    public QueryCriteria of(Field field, String value, CriteriaOptions options) {
      return new KeyValueCriteria(field, value, options, indexer);
    }

    public QueryCriteria of(QueryCriteria... criteria) {
      return new CompoundCriteria(Arrays.asList(criteria), indexer);
    }

    public CriteriaOptions options() {
      return new CriteriaOptions();
    }
  }

  private static class CriteriaOptions {

    private Occur occur;

    private boolean exact;

    private CriteriaOptions() {
      this.occur = Occur.MUST;
      this.exact = true;
    }

    public CriteriaOptions with(Occur occurrancePolicy) {
      this.occur = occurrancePolicy;
      return this;
    }

    public CriteriaOptions partialInput() {
      this.exact = false;
      return this;
    }
  }

  private abstract static class QueryCriteria extends Criteria {

    public QueryCriteria(CriteriaOptions options, Indexer indexer) {
      super(options, indexer);
    }

    public abstract Query getQuery();
  }

  private static class CompoundCriteria extends QueryCriteria {

    private final List<QueryCriteria> criteria;

    public CompoundCriteria(List<QueryCriteria> criteria, Indexer indexer) {
      super(new CriteriaOptions(), indexer);
      if (criteria == null || criteria.isEmpty()) {
        throw new IllegalArgumentException("Null or empty criteria is not supported");
      }
      this.criteria = criteria;
    }

    @Override
    public Query getQuery() {
      if (criteria.size() == 1) {
        return criteria.get(0).getQuery();
      }
      BooleanQuery.Builder builder = new BooleanQuery.Builder();
      criteria.forEach(c -> builder.add(c.getQuery(), c.getOptions().occur));
      return builder.build();
    }

    @Override
    public String toString() {
      return "["
          + criteria.stream().map(Criteria::toString).collect(Collectors.joining(", "))
          + "]";
    }
  }

  private static class KeyValueCriteria extends QueryCriteria {

    private final Field field;

    private final String value;

    private KeyValueCriteria(Field field, String value, CriteriaOptions options, Indexer indexer) {
      super(options, indexer);
      this.field = field;
      this.value = value;
    }

    @Override
    public Query getQuery() {
      return getIndexer()
          .constructQuery(
              field,
              getOptions().exact
                  ? new SourcedSearchExpression(value)
                  : new UserInputSearchExpression(value));
    }

    @Override
    public String toString() {
      return String.format("(%s %s %s)", field.getFieldName(), getOperator(), value);
    }

    private String getOperator() {
      if (getOptions().exact) {
        switch (getOptions().occur) {
          case MUST:
          case FILTER:
            return "MUST MATCH";
          case SHOULD:
            return "SHOULD MATCH";
          case MUST_NOT:
            return "MUST NOT MATCH";
        }
      } else {
        switch (getOptions().occur) {
          case MUST:
          case FILTER:
            return "MUST START WITH";
          case SHOULD:
            return "SHOULD START WITH";
          case MUST_NOT:
            return "MUST NOT START WITH";
        }
      }
      throw new IllegalStateException(
          String.format(
              "Unexpected combination of values, exact = '%s' and occurrance = '%s'",
              getOptions().exact, getOptions().occur));
    }
  }

  private static class VersionRangeFilter implements ArtifactInfoFilter {

    private final VersionScheme versionScheme = new GenericVersionScheme();

    // Nullable
    private final Version minVer;

    // Nullable
    private final Version maxVer;

    private final boolean exclusiveMin;

    private final boolean exclusiveMax;

    public static VersionRangeFilter atMinimum(String version)
        throws InvalidVersionSpecificationException {
      checkVersionString(version);
      return new VersionRangeFilter(version, null, false, false);
    }

    public static VersionRangeFilter atMaximum(String version)
        throws InvalidVersionSpecificationException {
      checkVersionString(version);
      return new VersionRangeFilter(null, version, false, false);
    }

    private VersionRangeFilter(
        String minVer, String maxVer, boolean exclusiveMin, boolean exclusiveMax)
        throws InvalidVersionSpecificationException {
      this.minVer = minVer == null ? null : versionScheme.parseVersion(minVer);
      this.maxVer = maxVer == null ? null : versionScheme.parseVersion(maxVer);
      this.exclusiveMin = exclusiveMin;
      this.exclusiveMax = exclusiveMax;
    }

    private VersionRangeFilter(
        Version minVer, Version maxVer, boolean exclusiveMin, boolean exclusiveMax) {
      this.minVer = minVer;
      this.maxVer = maxVer;
      this.exclusiveMin = exclusiveMin;
      this.exclusiveMax = exclusiveMax;
    }

    public VersionRangeFilter butStrictlyLessThan(String version)
        throws InvalidVersionSpecificationException {
      checkVersionString(version);
      return new VersionRangeFilter(
          this.minVer, versionScheme.parseVersion(version), this.exclusiveMin, true);
    }

    public VersionRangeFilter butStrictlyGreaterThan(String version)
        throws InvalidVersionSpecificationException {
      checkVersionString(version);
      return new VersionRangeFilter(
          versionScheme.parseVersion(version), this.maxVer, true, this.exclusiveMax);
    }

    @Override
    public boolean accepts(IndexingContext ctx, ArtifactInfo ai) {
      try {
        final Version artifactVersion = versionScheme.parseVersion(ai.getVersion());
        boolean matchesMin;
        if (minVer == null) {
          matchesMin = true;
        } else if (exclusiveMin) {
          matchesMin = artifactVersion.compareTo(minVer) > 0;
        } else {
          matchesMin = artifactVersion.compareTo(minVer) >= 0;
        }
        boolean matchesMax;
        if (maxVer == null) {
          matchesMax = true;
        } else if (exclusiveMax) {
          matchesMax = artifactVersion.compareTo(maxVer) < 0;
        } else {
          matchesMax = artifactVersion.compareTo(maxVer) <= 0;
        }
        return matchesMin && matchesMax;
      } catch (InvalidVersionSpecificationException e) {
        // Do something here? Be safe and include?
        return true;
      }
    }

    private static void checkVersionString(String version) {
      if (version == null || version.isEmpty()) {
        throw new IllegalArgumentException("Cannot supply a null or empty version");
      }
    }
  }

  private static void logline() {
    System.out.println();
  }

  private static void logline(String log) {
    System.out.println(log);
  }

  private void waitForUserToContinue() throws IOException {
    logline();
    logline("Press ENTER to continue");
    final String nextLine = consoleIn.readLine();
    if (!nextLine.trim().isEmpty()) {
      logline("Note: STDIN is being ignored");
    }
    logline();
    logline();
  }
}
