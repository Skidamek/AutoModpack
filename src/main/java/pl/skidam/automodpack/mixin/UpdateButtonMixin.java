package pl.skidam.automodpack.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.SelfUpdater;
import pl.skidam.automodpack.modpack.Modpack;

import java.util.List;

import static com.ibm.icu.impl.ValidIdentifiers.Datatype.u;
import static com.ibm.icu.impl.ValidIdentifiers.Datatype.x;
import static com.ibm.icu.text.PluralRules.Operand.v;
import static pl.skidam.automodpack.AutoModpack.TEXTURE;

@Mixin(TitleScreen.class)

public abstract class UpdateButtonMixin extends Screen {


    protected UpdateButtonMixin(Text title) {
        super(title);
    }
    public Text Button = Text.of("Check Updates!");

    @Inject(at = @At("RETURN"), method = "initWidgetsNormal" )
    private void AutoModpackUpdateButton(int y, int spacingY, CallbackInfo ci) {
        this.addDrawableChild(
                new TexturedButtonWidget(
                        this.width / 2 - 100 + 206, y, 32, 32,
                        0, 0, 0, TEXTURE, 32, 32,
                (button) -> {
            new Thread(new Modpack(0)).start();
            new Thread(new SelfUpdater(0)).start();

//            btn -> client.setScreen(this),
//
//            // Add a tooltip to greet the player
//            (btn, mtx, x, y) -> renderTooltip(mtx, new TranslatableText(
//                    "gui.automodpack.button.update.tooltip",
//                    new LiteralText("witam").formatted(Formatting.YELLOW)
//            ), x, y),
//            new TranslatableText("gui.automodpack.button.update")
        }));
    }
}


//            renderTooltip(
//                    Text.of("Checking for updates!"),
//                    new LiteralText("Please wait...").formatted(Formatting.YELLOW)
//            ), x, y)
            //render tooltip
            // Add a tooltip to greet the player
//            (btn, mtx, x, y) -> renderTooltip(mtx, new TranslatableText(
//                    "gui.authme.button.auth.tooltip",
//                    new LiteralText(("cos").formatted(Formatting.YELLOW)
//            ), x, y),
//            );
//
//            new TexturedButtonWidget(x, y, width, height, u, v, hoveredVOffset, new Identifier("example"), textureWidth, textureHeight, (button) -> {
//                System.out.println("Button Action");
//            }, (b, m, x, y) -> this.renderTooltip(m, List.of(new LiteralText("Tooltip Text")), x, y), LiteralText.EMPTY);

//            ButtonWidget.TooltipSupplier tooltipSupplier = new ButtonWidget.TooltipSupplier() {
//                @Override
//                public void onTooltip(ButtonWidget button, MatrixStack matrices, int y, int spacingY) {
//                    System.out.println("Button Action");
//                    renderTooltip(matrices, new TranslatableText("gui.automodpack.button.update.tooltip", new LiteralText("Check for updates").formatted(Formatting.RED)), 10, 10);
//                }
//            };




//        }));
//    }
//
////    @Inject(method = "render", at = @At("HEAD"))
////    public void renderTooltip() {
////
////    }
//}