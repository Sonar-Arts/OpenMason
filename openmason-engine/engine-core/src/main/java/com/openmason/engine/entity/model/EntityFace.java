package com.openmason.engine.entity.model;

/**
 * A single textured quad face of an entity model part.
 *
 * <p>Each face is two triangles (six indices) drawn with one material texture.
 * UVs for the face's vertices already span {@code 0..1} of that material in the
 * OMO mesh, so no atlas or UV remapping is needed.
 *
 * @param faceId     OMO face identifier
 * @param materialId material (and thus texture) this face uses
 * @param indexStart first index position in the combined index buffer
 * @param indexCount number of indices (always 6 for a quad)
 */
public record EntityFace(int faceId, int materialId, int indexStart, int indexCount) {
}
