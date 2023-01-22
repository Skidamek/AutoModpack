package pl.skidam.automodpack.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.AutoModpack;
@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    private static final Identifier ICON_TEXTURE = new Identifier(AutoModpack.MOD_ID, "button.png");
    public TitleScreenMixin(Text title) {
        super(title);
    }
    @Inject(at = @At("RETURN"), method = "initWidgetsNormal" )
    private void AutoModpackUpdateButton(int y, int spacingY, CallbackInfo ci) {
        int Y_BUTTON = 24;
        if (AutoModpack.isModMenu) {
            Y_BUTTON = 0;
        }

//        this.addDrawableChild(
//                new TexturedButtonWidget(this.width / 2 + 104, y + Y_BUTTON, 20, 20, 0, 0, 20, ICON_TEXTURE, 32, 64,
//                        button -> this.client.setScreen(new MenuScreen(MinecraftClient.getInstance().currentScreen)),
//                        Text.translatable("gui.automodpack.button.menu")
//                )
//        );
    }
}