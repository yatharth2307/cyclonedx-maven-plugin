package org.cyclonedx.maven;

import java.util.Collection;
import java.util.List;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallResult;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.resolution.VersionResult;

/**
 * Maven Resolver (Aether) repository system that delegates to provided system, but keep tracks of
 * collected dependencies result.
 * 
 * @see #getCollectResult()
 */
class DelegatingRepositorySystem implements RepositorySystem {
    private final RepositorySystem delegate;
    private CollectResult collectResult;

    public DelegatingRepositorySystem(final RepositorySystem repositorySystem) {
        this.delegate = repositorySystem;
    }

    public CollectResult getCollectResult() {
        return collectResult;
    }

    @Override
    public CollectResult collectDependencies(final RepositorySystemSession session, final CollectRequest request)
            throws DependencyCollectionException {
        collectResult = delegate.collectDependencies(session, request);
        return collectResult;
    }

    @Override
    public DeployResult deploy(final RepositorySystemSession session, final DeployRequest request)
            throws DeploymentException {
        return delegate.deploy(session, request);
    }

    @Override
    public InstallResult install(final RepositorySystemSession session, final InstallRequest request)
            throws InstallationException {
        return delegate.install(session, request);
    }

    @Override
    public RemoteRepository newDeploymentRepository(final RepositorySystemSession session, final RemoteRepository repository) {
        return delegate.newDeploymentRepository(session, repository);
    }

    @Override
    public LocalRepositoryManager newLocalRepositoryManager(final RepositorySystemSession session,
            final LocalRepository localRepository) {
        return delegate.newLocalRepositoryManager(session, localRepository);
    }

    @Override
    public List<RemoteRepository> newResolutionRepositories(final RepositorySystemSession session,
            final List<RemoteRepository> repositories) {
        return delegate.newResolutionRepositories(session, repositories);
    }

    @Override
    public SyncContext newSyncContext(final RepositorySystemSession session, final boolean shared) {
        return delegate.newSyncContext(session, shared);
    }

    @Override
    public ArtifactDescriptorResult readArtifactDescriptor(final RepositorySystemSession session,
            final ArtifactDescriptorRequest request) throws ArtifactDescriptorException {
        return delegate.readArtifactDescriptor(null, request);
    }

    @Override
    public ArtifactResult resolveArtifact(final RepositorySystemSession session, final ArtifactRequest request)
            throws ArtifactResolutionException {
        return delegate.resolveArtifact(session, request);
    }

    @Override
    public List<ArtifactResult> resolveArtifacts(final RepositorySystemSession session,
            final Collection<? extends ArtifactRequest> requests) throws ArtifactResolutionException {
        return delegate.resolveArtifacts(session, requests);
    }

    @Override
    public DependencyResult resolveDependencies(RepositorySystemSession session, DependencyRequest request) {
        validateSession(session);
        requireNonNull(request, "request cannot be null");

        RequestTrace trace = RequestTrace.newChild(request.getTrace(), request);

        DependencyResult result = new DependencyResult(request);

        DependencyCollectionException dce = null;
        ArtifactResolutionException are = null;

        if (request.getRoot() != null) {
            result.setRoot(request.getRoot());
        } else if (request.getCollectRequest() != null) {
            CollectResult collectResult;
            try {
                request.getCollectRequest().setTrace(trace);
                collectResult = dependencyCollector.collectDependencies(session, request.getCollectRequest());
            } catch (DependencyCollectionException e) {
                dce = e;
                collectResult = e.getResult();
            }
            result.setRoot(collectResult.getRoot());
            result.setCycles(collectResult.getCycles());
            result.setCollectExceptions(collectResult.getExceptions());
        } else {
            throw new NullPointerException("dependency node and collect request cannot be null");
        }

        ArtifactRequestBuilder builder = new ArtifactRequestBuilder(trace);
        DependencyFilter filter = request.getFilter();
        DependencyVisitor visitor = (filter != null) ? new FilteringDependencyVisitor(builder, filter) : builder;
        visitor = new TreeDependencyVisitor(visitor);

        if (result.getRoot() != null) {
            result.getRoot().accept(visitor);
        }

        List<ArtifactRequest> requests = builder.getRequests();

        List<ArtifactResult> results;
        try {
            results = artifactResolver.resolveArtifacts(session, requests);
        } catch (ArtifactResolutionException e) {
            are = e;
            results = e.getResults();
        }
        result.setArtifactResults(results);

        updateNodesWithResolvedArtifacts(results);

        return result;
    }

    @Override
    public List<MetadataResult> resolveMetadata(final RepositorySystemSession session,
            final Collection<? extends MetadataRequest> requests) {
        return delegate.resolveMetadata(session, requests);
    }

    @Override
    public VersionResult resolveVersion(final RepositorySystemSession session, final VersionRequest request)
            throws VersionResolutionException {
        return delegate.resolveVersion(session, request);
    }

    @Override
    public VersionRangeResult resolveVersionRange(final RepositorySystemSession session, final VersionRangeRequest request)
            throws VersionRangeResolutionException {
        return delegate.resolveVersionRange(session, request);
    }
}
