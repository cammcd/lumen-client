package dev.lumen.client.render;

import java.util.Optional;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import dev.lumen.client.Lumen;

/**
 * Render types for ESP geometry. Each comes in two flavours: one that respects the
 * depth buffer, and one with the depth test removed so it draws through walls.
 * Built lazily on first use from the render thread.
 */
public final class LumenRenderTypes {
	private static RenderType linesThroughWalls;
	private static RenderType linesDepth;
	private static RenderType quadsThroughWalls;
	private static RenderType quadsDepth;

	private LumenRenderTypes() {
	}

	private static void init() {
		if (linesThroughWalls != null) return;

		RenderPipeline.Snippet lines = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(false)
				.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
				.withPrimitiveTopology(PrimitiveTopology.LINES)
				.buildSnippet();

		RenderPipeline espLines = RenderPipelines.register(RenderPipeline.builder(lines)
				.withLocation(Lumen.id("pipeline/esp_lines"))
				.withDepthStencilState(Optional.empty())
				.build());

		RenderPipeline depthLines = RenderPipelines.register(RenderPipeline.builder(lines)
				.withLocation(Lumen.id("pipeline/depth_lines"))
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.build());

		RenderPipeline espQuads = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
				.withLocation(Lumen.id("pipeline/esp_quads"))
				.withDepthStencilState(Optional.empty())
				.withCull(true)
				.build());

		RenderPipeline depthQuads = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
				.withLocation(Lumen.id("pipeline/depth_quads"))
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.withCull(true)
				.build());

		linesThroughWalls = RenderType.create("lumen:esp_lines", RenderSetup.builder(espLines)
				.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
				.createRenderSetup());
		linesDepth = RenderType.create("lumen:depth_lines", RenderSetup.builder(depthLines)
				.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
				.createRenderSetup());
		quadsThroughWalls = RenderType.create("lumen:esp_quads", RenderSetup.builder(espQuads)
				.sortOnUpload()
				.createRenderSetup());
		quadsDepth = RenderType.create("lumen:depth_quads", RenderSetup.builder(depthQuads)
				.sortOnUpload()
				.createRenderSetup());
	}

	public static RenderType lines(boolean throughWalls) {
		init();
		return throughWalls ? linesThroughWalls : linesDepth;
	}

	public static RenderType quads(boolean throughWalls) {
		init();
		return throughWalls ? quadsThroughWalls : quadsDepth;
	}
}
