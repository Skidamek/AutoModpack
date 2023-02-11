//package pl.skidam.automodpack.mixin;
//
//import com.google.common.collect.Sets;
//import net.minecraft.client.MinecraftClient;
//import net.minecraft.resource.ResourcePackManager;
//import net.minecraft.resource.ResourcePackProfile;
//import net.minecraft.text.Text;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Inject;
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//
//import java.util.Iterator;
//import java.util.Set;
//
//@Mixin(value = MinecraftClient.class, priority = 2137)
//public class ForcePacks {
//    @Inject(method = "<init>", at = @At("TAIL"), cancellable = true)
//    public void enableOursPacks(CallbackInfo ci) {
//
//    }
//
//    public void addResourcePackProfilesToManager(ResourcePackManager manager) {
//        Set<String> set = Sets.newLinkedHashSet();
//        Iterator<String> iterator = this.resourcePacks.iterator();
//
//        while(iterator.hasNext()) {
//            String s = (String)iterator.next();
//            ResourcePackProfile pack = manager.getProfile(s);
//            if (pack == null && !s.startsWith("file/")) {
//                pack = manager.getProfile("file/" + s);
//            }
//
//            if (pack == null) {
//                iterator.remove();
//            } else if (!pack.getCompatibility().isCompatible()) {
//                iterator.remove();
//            } else {
//                set.add(pack.getName());
//            }
//        }
//
//        manager.setEnabledProfiles(set);
//    }
//}