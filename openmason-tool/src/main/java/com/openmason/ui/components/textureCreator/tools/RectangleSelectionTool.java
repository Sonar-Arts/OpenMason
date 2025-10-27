package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.selection.RectanglePreview;
import com.openmason.ui.components.textureCreator.tools.selection.RectangleSelectionStrategy;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionPreview;
import com.openmason.ui.components.textureCreator.tools.selection.SelectionToolController;

/**
 * Rectangle selection tool - creates rectangular selection regions.
 * Click and drag to create a new rectangular selection.
 *
 * REFACTORED: Now uses SelectionToolController with RectangleSelectionStrategy.
 * This is a compatibility wrapper that maintains the existing API.
 *
 * Architecture:
 * - Controller: SelectionToolController (coordinates tool lifecycle)
 * - Strategy: RectangleSelectionStrategy (implements rectangle drag logic)
 *
 * SOLID: Single responsibility - delegates to controller and strategy
 * KISS: Simple wrapper around controller
 * DRY: Reuses common selection logic from controller
 *
 * @author Open Mason Team
 */
public class RectangleSelectionTool implements DrawingTool {

    // Delegate to controller with rectangle strategy
    private final SelectionToolController controller;

    // Tool options
    private boolean fixedAspectRatio = false; // When true, constrains selection to 1:1 square ratio

    /**
     * Creates a rectangle selection tool with default strategy.
     */
    public RectangleSelectionTool() {
        this.controller = new SelectionToolController(new RectangleSelectionStrategy());
    }

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        controller.onMouseDown(x, y, color, canvas, command);
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        controller.onMouseDrag(x, y, color, canvas, command);
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        controller.onMouseUp(color, canvas, command);
    }

    /**
     * Get the selection bounds for preview rendering.
     * Used by CanvasPanel to render selection preview during drag.
     *
     * @return array [startX, startY, endX, endY] or null if not selecting
     */
    public int[] getSelectionPreviewBounds() {
        SelectionPreview preview = controller.getPreview();
        if (preview instanceof RectanglePreview) {
            RectanglePreview rectPreview = (RectanglePreview) preview;
            return new int[]{
                rectPreview.getStartX(),
                rectPreview.getStartY(),
                rectPreview.getEndX(),
                rectPreview.getEndY()
            };
        }
        return null;
    }

    /**
     * Get the created selection region.
     * CanvasPanel should read this after onMouseUp to update state.
     *
     * @return The created selection, or null if no selection was created
     */
    public SelectionRegion getCreatedSelection() {
        return controller.getCreatedSelection();
    }

    /**
     * Check if a selection was created during the last drag operation.
     * This includes both new selections and explicit clear (single-point click).
     *
     * @return true if selection state changed
     */
    public boolean wasSelectionCreated() {
        return controller.hasSelection();
    }

    /**
     * Clear the created selection flag.
     * Should be called by CanvasPanel after reading the selection.
     */
    public void clearSelectionCreatedFlag() {
        controller.clearSelectionFlag();
    }

    @Override
    public void reset() {
        controller.reset();
        fixedAspectRatio = false; // Reset fixed aspect ratio option
    }

    /**
     * Sets whether aspect ratio should be fixed (1:1) during selection.
     * When true, constrains rectangle to a square.
     *
     * @param fixed true to fix aspect ratio, false to allow free-form rectangles
     */
    public void setFixedAspectRatio(boolean fixed) {
        this.fixedAspectRatio = fixed;
    }

    /**
     * Checks if aspect ratio is fixed for rectangle selection.
     *
     * @return true if aspect ratio is fixed, false otherwise
     */
    public boolean isFixedAspectRatio() {
        return fixedAspectRatio;
    }

    @Override
    public String getName() {
        return controller.getName();
    }

    @Override
    public String getDescription() {
        return controller.getDescription();
    }
}
