package com.zhongbai233.scene_editor.core.session;

import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.core.selection.MultiSelectionPolicy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicitly owned editor session state. It intentionally owns no renderer, window, network or serialization object.
 */
public final class EditorSession<E extends SceneElement> implements AutoCloseable {
    private final CommandStack<SceneDocument<E>> commands;
    private SceneDocument<E> document;
    private EditorCameraState camera;
    private EditorViewport viewport;
    private final LinkedHashSet<UUID> selectedElementIds = new LinkedHashSet<>();
    private UUID primaryElementId;
    private boolean closed;

    private EditorSession(SceneDocument<E> document, EditorCameraState camera, EditorViewport viewport,
            int historyCapacity) {
        this.document = Objects.requireNonNull(document, "document");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.viewport = Objects.requireNonNull(viewport, "viewport");
        this.commands = new CommandStack<>(historyCapacity);
    }

    public static <E extends SceneElement> EditorSession<E> open(SceneDocument<E> document,
            EditorCameraState camera, EditorViewport viewport, int historyCapacity) {
        return new EditorSession<>(document, camera, viewport, historyCapacity);
    }

    public SceneDocument<E> document() {
        requireOpen();
        return document;
    }

    public void document(SceneDocument<E> document) {
        requireOpen();
        this.document = Objects.requireNonNull(document, "document");
        selectedElementIds.removeIf(id -> document.element(id).isEmpty());
        if (primaryElementId != null && !selectedElementIds.contains(primaryElementId)) {
            primaryElementId = selectedElementIds.isEmpty() ? null : selectedElementIds.getLast();
        }
    }

    public CommandStack<SceneDocument<E>> commands() {
        requireOpen();
        return commands;
    }

    public EditorCameraState camera() {
        requireOpen();
        return camera;
    }

    public void camera(EditorCameraState camera) {
        requireOpen();
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    public EditorViewport viewport() {
        requireOpen();
        return viewport;
    }

    public void resize(EditorViewport viewport) {
        requireOpen();
        this.viewport = Objects.requireNonNull(viewport, "viewport");
    }

    public Optional<UUID> selectedElementId() {
        requireOpen();
        return Optional.ofNullable(primaryElementId);
    }

    public List<UUID> selectedElementIds() {
        requireOpen();
        return List.copyOf(selectedElementIds);
    }

    public void select(UUID elementId) {
        requireOpen();
        Objects.requireNonNull(elementId, "elementId");
        if (document.element(elementId).isEmpty()) {
            throw new IllegalArgumentException("scene element is not part of this document: " + elementId);
        }
        selectedElementIds.clear();
        selectedElementIds.add(elementId);
        primaryElementId = elementId;
    }

    public void select(Collection<UUID> elementIds, UUID primaryId) {
        requireOpen();
        Objects.requireNonNull(elementIds, "elementIds");
        LinkedHashSet<UUID> checked = new LinkedHashSet<>(elementIds);
        for (UUID id : checked) {
            if (document.element(Objects.requireNonNull(id, "elementId")).isEmpty()) {
                throw new IllegalArgumentException("scene element is not part of this document: " + id);
            }
        }
        if (primaryId != null && !checked.contains(primaryId)) {
            throw new IllegalArgumentException("primary element must be selected");
        }
        selectedElementIds.clear();
        selectedElementIds.addAll(checked);
        primaryElementId = primaryId;
    }

    public void clickSelect(UUID clickedElementId, boolean shiftDown, boolean controlDown) {
        requireOpen();
        List<UUID> orderedIds = document.elements().stream().map(SceneElement::id).toList();
        MultiSelectionPolicy.Result result = MultiSelectionPolicy.click(orderedIds, selectedElementIds,
                primaryElementId, clickedElementId, shiftDown, controlDown);
        select(result.selectedElementIds(), result.primaryElementId());
    }

    public void clearSelection() {
        requireOpen();
        selectedElementIds.clear();
        primaryElementId = null;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        selectedElementIds.clear();
        primaryElementId = null;
        commands.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("editor session is closed");
        }
    }
}
