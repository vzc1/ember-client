package net.emberclient.fabric;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** A mod as seen in the browser: real metadata, no files on disk. */
final class EmberModContainer implements ModContainer {
	private final ModMetadata metadata;
	private final boolean builtin;

	EmberModContainer(ModMetadata metadata, boolean builtin) {
		this.metadata = metadata;
		this.builtin = builtin;
	}

	@Override public ModMetadata getMetadata() { return metadata; }
	@Override public List<Path> getRootPaths() { return Collections.emptyList(); }
	@Override public Optional<Path> findPath(String file) { return Optional.empty(); }
	@Override public ModOrigin getOrigin() { return ORIGIN; }
	@Override public Optional<ModContainer> getContainingMod() { return Optional.empty(); }
	@Override public Collection<ModContainer> getContainedMods() { return Collections.emptyList(); }
	@Override public Path getRootPath() { return EmberFabric.gameDir(); }
	@Override public Path getPath(String file) { return EmberFabric.gameDir().resolve(file); }
	@Override public String toString() { return metadata.getId() + " " + metadata.getVersion().getFriendlyString(); }

	boolean isBuiltin() { return builtin; }

	private static final ModOrigin ORIGIN = new ModOrigin() {
		@Override public Kind getKind() { return Kind.UNKNOWN; }
		@Override public List<Path> getPaths() { throw new UnsupportedOperationException("baked into the browser build"); }
		@Override public String getParentModId() { throw new UnsupportedOperationException(); }
		@Override public String getParentSubLocation() { throw new UnsupportedOperationException(); }
	};
}
