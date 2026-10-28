package net.emberclient.core.mixin;

import net.emberclient.core.EmberCore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws "Ember Client" in the title screen corner: visible proof that mixins work. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void ember$drawBrand(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		String label = EmberCore.initialized ? "Ember Client" : "Ember Client (entrypoint did not run)";
		graphics.text(this.font, label, 2, 2, 0xFFFF7A3D);
	}
}
