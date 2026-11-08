package com.openmason.main.systems.mcp;

/**
 * Result type for tools that return an image AND structured data about it (a capture plus the camera and run
 * report it was taken with): {@link McpRequestRouter} emits two content blocks, the image then the data as JSON
 * text; the in-tool assistant shows the image and reads the text.
 *
 * @param image the image
 * @param data  any JSON-serialisable value
 */
public record McpImageWithData(McpImageContent image, Object data) {}
