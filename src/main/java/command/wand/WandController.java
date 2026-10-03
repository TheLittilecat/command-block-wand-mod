package command.wand;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class WandController {
    private static final int SINGLE_VISUAL_TICKS = 4;
    private static final int RELEASE_TAIL_TICKS = 2;
    private static final int DOT_COLOR = 0xFFFFFFFF;
    private static final double PICK_RANGE = 256.0;
    /** @f 命令坐标相对命中点的偏移：X 方向 -1、Y 方向 -1。 */
    private static final double FORWARD_X = -1.0;
    private static final double FORWARD_DROP = 1.0;

    private static final double ANCHOR_LIFT = 0.5;
    private static final double ANCHOR_EXTRA_LIFT = 0.3;

    private static final double SCREEN_OFFSET_X = 0.52;
    private static final double SCREEN_OFFSET_Y = 3.0;

    private static final double FOLD = 0.16;

    final List<Visual> visuals = new ArrayList<>();
    private final Map<String, PendingPulse> pulses = new LinkedHashMap<>();
    private boolean wasRightDown;
    /** true 表示必须先检测到一次真正的松开，才允许再次触发释放。 */
    private boolean rightLatched;
    private int repeatCountdown;
    private ClientLevel watchedLevel;

    private int lockedSlot = -1;

    private static int recentFillAt = -1;

    // ---- 滚轮调距 ----

    private void handleAltScroll(Minecraft mc, LocalPlayer player) {
        boolean adjusting = mc.hasAltDown()
                && player.getInventory().getSelectedItem().is(Items.STICK)
                && mc.gui.screen() == null && mc.gui.overlay() == null;
        if (adjusting && lockedSlot < 0) {
            lockedSlot = player.getInventory().getSelectedSlot();
        }
        if (!adjusting) {
            lockedSlot = -1;

            ScrollDelta.takeY();
        }

        ScrollDelta.setSuppressOriginal(adjusting);
        if (!adjusting) return;

        double scroll = ScrollDelta.takeY();
        if (scroll == 0.0) return;
        SkillSlot slot = CommandWandClient.config.active();
        slot.distance = SkillSlot.clampDistance(
                slot.distance + SkillSlot.scrollDelta(slot.distance, scroll));
        CommandWandClient.config.save();

        if (player.getInventory().getSelectedSlot() != lockedSlot) {
            player.getInventory().setSelectedSlot(lockedSlot);
        }
    }

    // ---- 白点连线的数据 ----

    static final class Visual {
        final String command;
        final SkillSlot slot;
        Entity target;
        Vec3 anchor;
        Vec3 dotEnd;
        int ttl;
        boolean follow;

        private Vector2f smoothed;

        Visual(String command, SkillSlot slot, Entity target, Vec3 anchor, Vec3 dotEnd, int ttl, boolean follow) {
            this.command = command;
            this.slot = slot;
            this.target = target;
            this.anchor = anchor;
            this.dotEnd = dotEnd;
            this.ttl = ttl;
            this.follow = follow;
        }

        void refresh(Entity nextTarget, Aim aim) {
            target = nextTarget;
            anchor = aim.forwardCommandPoint();
            dotEnd = aim.dotPoint(slot, nextTarget);
        }

        Vector2f smoothScreenTarget(Minecraft mc, Vector2f target) {
            if (smoothed == null) {
                smoothed = target;
                return smoothed;
            }

            float seconds = Math.min(0.1F, Math.max(1.0F / 240.0F, mc.getDeltaTracker().getRealtimeDeltaTicks()));
            float k = 1.0F - (float) Math.pow(0.02, seconds);
            smoothed = new Vector2f(
                    smoothed.x() + (target.x() - smoothed.x()) * k,
                    smoothed.y() + (target.y() - smoothed.y()) * k);
            return smoothed;
        }

        /** 跳过平滑时同步缓存，避免重新进入屏幕后从陈旧位置渐变。 */
        void resetSmooth(Vector2f value) {
            smoothed = value;
        }
    }

    private static final class PendingPulse {
        int ticksLeft;
        SkillSlot slot;
        Entity target;
        Vec3 anchor;
    }

    // ---- 一次瞄准的结果 ----

    record Aim(Vec3 forwardCommandPoint, Vec3 forwardHitPoint, Vec3 crosshairEnd,
               Vec3 blockFrontPoint, boolean forwardBlocked, boolean crosshairBlocked) {
        static final Aim EMPTY = new Aim(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, false, false);

        Vec3 dotPoint(SkillSlot slot, Entity target) {
            if (target != null) return forwardHitPoint;
            if (WandController.containsSelector(slot.start, 'f')) return forwardHitPoint;
            if (WandController.containsSelector(slot.start, 't') && crosshairBlocked) return blockFrontPoint;
            return forwardHitPoint;
        }
    }

    // ---- 每 tick ----

    void lockAltScroll(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            lockedSlot = -1;
            ScrollDelta.setSuppressOriginal(false);
            ScrollDelta.takeY();
            return;
        }
        handleAltScroll(mc, player);
    }

    void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            pulses.clear();
            visuals.clear();
            wasRightDown = false;
            rightLatched = true;
            watchedLevel = null;
            return;
        }

        handleAltScroll(mc, player);
        if (watchedLevel != level) {
            watchedLevel = level;
            pulses.clear();
            visuals.clear();
        }
        if (mc.getConnection() != null && !(mc.gameMode instanceof WandGameMode)) {
            mc.gameMode = new WandGameMode(mc, mc.getConnection());
        }

        boolean stickHeld = player.getInventory().getSelectedItem().is(Items.STICK);
        handleConfigKey(mc, stickHeld);
        maybeOpenWheel(mc, stickHeld);
        handleRightButton(mc, player, stickHeld);
        tickPulses(mc);
        tickVisuals();
    }

    // ---- 按键与界面 ----

    private void handleConfigKey(Minecraft mc, boolean stickHeld) {

        if (CommandWandClient.configKey.consumeClick() && stickHeld && mc.gui.screen() == null && mc.gui.overlay() == null) {
            mc.setScreenAndShow(new WheelScreen());
        }
    }

    private void maybeOpenWheel(Minecraft mc, boolean stickHeld) {
        if (stickHeld && mc.gui.screen() == null && mc.gui.overlay() == null && CommandWandClient.wheelKey.isDown()) {
            mc.gui.setOverlay(new WandWheelOverlay());
        }
    }

    /**
     * 右键触发的开合。
     *
     * <p>只要「释放条件」被外力打断——死亡、开了界面、切世界、窗口失焦、手上的不是木棍——
     * 就一律当作玩家已经松手：收掉持续释放，并上锁，必须真正检测到一次松开才允许再次触发。
     *
     * <p>不上锁会残留出一个很难发现的状态：死亡前没松手，死亡过程中 {@code wasRightDown}
     * 被清成 false，而物理右键还按着，重生那一帧 {@code rightDown} 重新变 true，
     * 于是走进「上升沿」分支自动重新触发，表现为拿着木棍就自己一直在放。
     */
    private void handleRightButton(Minecraft mc, LocalPlayer player, boolean stickHeld) {
        boolean rawPressed = mc.mouseHandler.isRightPressed();
        if (!rawPressed) {
            rightLatched = false;
        }
        if (player.isDeadOrDying() || !stickHeld
                || mc.gui.screen() != null || mc.gui.overlay() != null
                || !mc.getWindow().isFocused()) {
            abortRelease(mc);
            return;
        }
        SkillSlot active = CommandWandClient.config.active();
        boolean rightDown = rawPressed && !rightLatched;
        if (rightDown && !wasRightDown) {
            if (active.armed()) {
                press(mc, active);
                repeatCountdown = Math.max(1, active.interval);
            }
        } else if (rightDown && active.type == SkillType.REPEAT && active.armed()) {
            if (--repeatCountdown <= 0) {
                pressRepeatTick(mc, active);
                repeatCountdown = Math.max(1, active.interval);
            }
        } else if (!rightDown && wasRightDown) {
            releaseRepeat(mc);
        }
        wasRightDown = rightDown;
    }

    /** 打断持续释放：收掉跟随中的白点，并锁住右键直到玩家真正松开一次。 */
    private void abortRelease(Minecraft mc) {
        if (wasRightDown) {
            releaseRepeat(mc);
        }
        wasRightDown = false;
        rightLatched = true;
    }

    // ---- 技能释放 ----

    private void press(Minecraft mc, SkillSlot slot) {
        triggerFlash();
        Aim aim = aim(mc, slot.distance);
        Entity target = resolveTarget(mc, slot, aim);
        if (slot.type == SkillType.REPEAT) {
            sendPrepared(mc, slot.start, target, aim.forwardCommandPoint(), slot);
            Visual old = activeVisual();
            if (old != null) old.refresh(target, aim);
            else visuals.add(new Visual(slot.start, slot, target, aim.forwardCommandPoint(),
                    aim.dotPoint(slot, target), Integer.MAX_VALUE / 4, true));
            return;
        }
        String command = buildCommand(mc, slot.start, target, aim.forwardCommandPoint(), slot.truncateCoordinates);
        if (command == null) return;
        send(mc, command);
        if (slot.type == SkillType.SINGLE) {
            addFixedVisuals(mc, slot, target, aim, SINGLE_VISUAL_TICKS);
        } else {
            addFixedVisuals(mc, slot, target, aim, Math.max(1, slot.delay) + RELEASE_TAIL_TICKS);
            PendingPulse pulse = new PendingPulse();
            pulse.slot = slot;
            pulse.target = target;
            pulse.anchor = aim.forwardCommandPoint();
            pulse.ticksLeft = Math.max(1, slot.delay);
            pulses.put(pulseKey(target, aim.forwardCommandPoint()), pulse);
        }
    }

    private void pressRepeatTick(Minecraft mc, SkillSlot slot) {
        triggerFlash();
        // 每 tick 自己重新瞄准，不复用 visual 里的缓存。
        // 那份缓存只在渲染时被 refreshFollow 刷新，而渲染第一人称才跑、还可能整帧跳过，
        // 一旦没刷新，@f 的坐标就冻在按下那一刻，改距离和转视角都不生效。
        Aim aim = aim(mc, slot.distance);
        Entity target = resolveTarget(mc, slot, aim);
        Visual visual = activeVisual();
        if (visual != null) {
            visual.refresh(target, aim);
        }
        sendPrepared(mc, slot.start, target, aim.forwardCommandPoint(), slot);
    }

    private void releaseRepeat(Minecraft mc) {
        Visual visual = activeVisual();
        if (visual == null) return;
        visual.follow = false;
        visual.ttl = RELEASE_TAIL_TICKS;
    }

    private void tickPulses(Minecraft mc) {
        Iterator<Map.Entry<String, PendingPulse>> it = pulses.entrySet().iterator();
        while (it.hasNext()) {
            PendingPulse pulse = it.next().getValue();
            if (--pulse.ticksLeft <= 0) {
                sendPrepared(mc, pulse.slot.end, pulse.target, pulse.anchor, pulse.slot);
                it.remove();
            }
        }
    }

    private void tickVisuals() {
        visuals.removeIf(v -> !v.follow && v.ttl <= 0);
        for (Visual v : visuals) {
            if (!v.follow && v.ttl < Integer.MAX_VALUE / 8) v.ttl--;
        }
    }

    // ---- 命令发送 ----

    private String buildCommand(Minecraft mc, String raw, Entity target, Vec3 anchor, boolean truncate) {
        if (raw == null || raw.isBlank()) return null;
        StringBuilder command = new StringBuilder(stripSlash(raw));
        Vec3 forward = look(mc);
        Vec3 left = left(forward);
        Vec3 up = forward.cross(left).normalize();
        if (CommandSubstituter.apply(command, target != null ? target.getUUID() : null,
                anchor, forward, left, up, truncate) != null) return null;
        return command.toString();
    }

    private void sendPrepared(Minecraft mc, String raw, Entity target, Vec3 anchor, SkillSlot slot) {
        send(mc, buildCommand(mc, raw, target, anchor, slot.truncateCoordinates));
    }

    private Entity resolveTarget(Minecraft mc, SkillSlot slot, Aim aim) {
        if (!containsSelector(slot.start, 't')) return null;
        if (mc.player == null || mc.level == null) return null;
        return nearestOnRay(mc, mc.player.getEyePosition(), aim.crosshairEnd());
    }

    // ---- 射线与遮挡 ----

    /**
     * 计算 @f 的瞄准结果。
     *
     * <p>遮挡探测用一条与技能距离无关的长射线（{@link #PICK_RANGE}）：
     * 距离只该决定「取多远处的坐标」，不该决定「能不能看见近处的方块」。
     * 早先射线长度直接等于 distance（默认 0.5 格），准星指着的方块在几格外，
     * 射线根本碰不到，遮挡判定因此永远失效。
     */
    private Aim aim(Minecraft mc, double distance) {
        if (mc.player == null || mc.level == null) return Aim.EMPTY;
        Vec3 start = mc.player.getEyePosition();
        Vec3 direction = mc.player.getViewVector(0.0F);

        // 遮挡探测射线：足够长，近处方块与实体都能被看到
        Vec3 probeEnd = start.add(direction.scale(PICK_RANGE));
        BlockHitResult forwardHit = clipBlocks(mc, start, probeEnd);
        boolean blockHit = forwardHit != null && forwardHit.getType() == HitResult.Type.BLOCK;
        Vec3 blockPoint = blockHit ? forwardHit.getLocation() : null;

        Entity blocker = nearestOnRay(mc, start, probeEnd);
        Vec3 entityPoint = blocker != null ? blocker.getBoundingBox().clip(start, probeEnd).orElse(null) : null;

        // 方块与实体取更近者作为实际遮挡物
        boolean blockBlocks = blockPoint != null
                && (entityPoint == null || blockPoint.distanceToSqr(start) <= entityPoint.distanceToSqr(start));

        // 技能距离决定落点：未被遮挡时落在 distance 处
        Vec3 forwardEnd = start.add(direction.scale(Math.max(SkillSlot.MIN_DISTANCE, distance)));
        Vec3 forwardCommand;
        Vec3 dotEnd;
        if (blockBlocks) {
            // 命中点在方块表面，用中心才不会让 setblock/fill 落到相邻格
            forwardCommand = Vec3.atCenterOf(forwardHit.getBlockPos());
            dotEnd = blockPoint;
        } else if (entityPoint != null) {
            forwardCommand = blocker.getPosition(0.0F);
            dotEnd = entityPoint;
        } else {
            forwardCommand = forwardEnd;
            dotEnd = forwardEnd;
        }
        // 命令坐标固定偏移：X -1、Y -1。
        // 只改世界坐标的两个轴，与玩家朝向无关，因此不会让结果随朝向变化。
        forwardCommand = forwardCommand.add(FORWARD_X, -FORWARD_DROP, 0.0);
        boolean forwardBlocked = blockPoint != null || entityPoint != null;

        Vec3 crosshairFar = start.add(direction.scale(PICK_RANGE));
        BlockHitResult crossHit = clipBlocks(mc, start, crosshairFar);
        boolean crosshairBlocked = crossHit != null && crossHit.getType() == HitResult.Type.BLOCK;
        Vec3 crosshairEnd = crosshairBlocked ? crossHit.getLocation() : crosshairFar;

        Vec3 blockFront = crosshairBlocked
                ? Vec3.atCenterOf(crossHit.getBlockPos().relative(crossHit.getDirection()))
                : crosshairEnd;
        return new Aim(forwardCommand, dotEnd, crosshairEnd, blockFront, forwardBlocked, crosshairBlocked);
    }

    private BlockHitResult clipBlocks(Minecraft mc, Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    private Entity nearestOnRay(Minecraft mc, Vec3 start, Vec3 end) {
        Set<Entity> candidates = new LinkedHashSet<>();
        for (Entity entity : mc.level.getEntities(mc.player, segmentBox(start, end), WandController::pickable)) {
            candidates.add(entity);
        }
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : candidates) {
            // 用真实碰撞箱判断，不用 inflate(pickRadius)：
            // 膨胀后射线擦着实体边缘也算命中，会隔墙打到墙后的生物。
            Optional<Vec3> hit = entity.getBoundingBox().clip(start, end);
            if (hit.isPresent()) {
                double distance = start.distanceToSqr(hit.get());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entity;
                }
            }
        }
        return best;
    }

    private static AABB segmentBox(Vec3 start, Vec3 end) {
        double length = start.distanceTo(end);
        int segments = (int) Math.min(16, Math.max(1, Math.ceil(length / 32.0)));
        double minX = start.x, minY = start.y, minZ = start.z;
        double maxX = start.x, maxY = start.y, maxZ = start.z;
        for (int i = 1; i <= segments; i++) {
            Vec3 point = start.lerp(end, (double) i / segments);
            minX = Math.min(minX, point.x - 2);
            minY = Math.min(minY, point.y - 2);
            minZ = Math.min(minZ, point.z - 2);
            maxX = Math.max(maxX, point.x + 2);
            maxY = Math.max(maxY, point.y + 2);
            maxZ = Math.max(maxZ, point.z + 2);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean pickable(Entity entity) {
        return !entity.isRemoved() && entity.isPickable() && !entity.isSpectator();
    }

    static boolean containsSelector(String command, char kind) {
        if (command == null) return false;
        for (int i = 0; i + 1 < command.length(); i++) {
            if (command.charAt(i) != '@' || command.charAt(i + 1) != kind) continue;
            if (i + 2 >= command.length() || !isNameChar(command.charAt(i + 2))) return true;
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    // ---- 视角基向量 ----

    private Vec3 look(Minecraft mc) {
        return mc != null && mc.player != null ? mc.player.getViewVector(0.0F) : new Vec3(0, 0, 1);
    }

    private static Vec3 left(Vec3 forward) {
        Vec3 v = new Vec3(0, 1, 0).cross(forward);
        return v.lengthSqr() < 1.0e-8 ? new Vec3(1, 0, 0) : v.normalize();
    }

    // ---- 白点收集 ----

    private void addFixedVisuals(Minecraft mc, SkillSlot slot, Entity target, Aim aim, int ttl) {
        if (target != null) {
            visuals.add(new Visual(slot.start, slot, target, aim.forwardCommandPoint(), aim.dotPoint(slot, target), ttl, false));
            return;
        }
        if (containsSelector(slot.start, 'f') || containsSelector(slot.start, 't')) {
            visuals.add(new Visual(slot.start, slot, null, aim.forwardCommandPoint(), aim.dotPoint(slot, null), ttl, false));
            return;
        }
        List<Entity> selected = vanillaSelectorTargets(mc, slot.start);
        if (selected.isEmpty()) {
            visuals.add(new Visual(slot.start, slot, null, aim.forwardCommandPoint(), aim.forwardHitPoint(), ttl, false));
        } else {
            for (Entity entity : selected) {
                visuals.add(new Visual(slot.start, slot, entity, aim.forwardCommandPoint(),
                        entity.getPosition(0.0F).add(0.0, entity.getBbHeight() * 0.5, 0.0), ttl, false));
            }
        }
    }

    private List<Entity> vanillaSelectorTargets(Minecraft mc, String command) {
        List<Entity> out = new ArrayList<>();
        if (mc.player == null || mc.level == null || command == null) return out;
        if (containsSelector(command, 's')) {
            out.add(mc.player);
            return out;
        }
        boolean entities = command.contains("@e");
        boolean players = command.contains("@a") || command.contains("@p") || command.contains("@r");
        if (!entities && !players) return out;
        double range = distanceArgument(command);
        AABB area = mc.player.getBoundingBox().inflate(range);
        String typeFilter = stringArgument(command, "type=");
        for (Entity entity : mc.level.getEntities(mc.player, area, WandController::pickable)) {
            if (!(entities || entity instanceof Player)) continue;
            if (entity.distanceToSqr(mc.player) > range * range) continue;
            if (typeFilter != null && !typeFilter.isBlank()
                    && !entity.getType().getDescriptionId().contains(typeFilter.replace("minecraft:", ""))) continue;
            out.add(entity);
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
        int limit = intArgument(command, "limit=");
        if (command.contains("@p") || command.contains("@r")) limit = Math.max(1, limit == -1 ? 1 : limit);
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    private static double distanceArgument(String command) {
        String raw = stringArgument(command, "distance=");
        if (raw == null) return 64.0;
        int dot = raw.indexOf("..");
        String value = dot >= 0 ? raw.substring(dot + 2) : raw;
        try {
            return SkillSlot.clampDistance(Double.parseDouble(value));
        } catch (RuntimeException ignored) {
            return 64.0;
        }
    }

    private static int intArgument(String command, String key) {
        String raw = stringArgument(command, key);
        try {
            return raw == null ? -1 : Integer.parseInt(raw);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String stringArgument(String command, String key) {
        int from = command.indexOf(key);
        if (from < 0) return null;
        int start = from + key.length();
        int end = start;
        while (end < command.length() && command.charAt(end) != ']' && command.charAt(end) != ',') end++;
        return command.substring(start, end);
    }

    /**
     * 屏幕平面圆点。
     *
     * <p>终点分两种情况：
     * <ul>
     *   <li><b>命令没有世界目标</b>（与位置无关的命令，或目标就是玩家自己）——
     *       终点固定在屏幕下边缘中点；</li>
     *   <li><b>有世界目标但在屏幕外</b>——终点取「起点→目标」射线与屏幕边界的交点，
     *       连线自由延伸指向屏幕外，不被拉伸变形；</li>
     *   <li>目标在屏内——直接画到投影点。</li>
     * </ul>
     *
     * @return 是否实际画出了至少一个点（调用方据此决定是否显示指令框）
     */
    boolean renderScreenDots(GuiGraphicsExtractor g, Minecraft mc, float partialTick) {
        if (mc.level == null || mc.player == null || visuals.isEmpty()) return false;
        if (!mc.options.getCameraType().isFirstPerson()) return false;
        Vector2f originScreen = screenLineStart(mc);
        if (originScreen == null) return false;

        int width = g.guiWidth();
        int height = g.guiHeight();
        Vector2f noTarget = new Vector2f(width / 2.0F, height);
        boolean drawn = false;

        for (Visual visual : visuals) {
            refreshFollow(visual, mc);
            Vec3 end = endpoint(mc, visual, partialTick);
            Vector2f target;
            boolean outsideScreen = false;
            if (end == null) {
                // 与位置无关的命令：没有可信的连接点，固定收到屏幕下边缘中点
                target = noTarget;
            } else {
                Vec3 endScreen = screenPoint(mc, end);
                if (endScreen == null) {
                    // 目标在相机背后。目标依然存在，只是投影退化（w<=0），
                    // 不能当作「无目标」。改用「目标相对相机」的水平方向，
                    // 指向那一侧的画面边缘——背对时连线指离屏幕中心。
                    target = behindTargetEdge(mc, end, originScreen, width, height);
                    outsideScreen = true;
                } else {
                    float px = (float) endScreen.x;
                    float py = (float) endScreen.y;
                    if (px < 0 || px > width || py < 0 || py > height) {
                        // 目标在屏幕外：直接用边界交点，不做平滑。
                        // 平滑会把上一帧的屏内位置渐变到边界，看起来像被强行吸附。
                        target = clampToScreen(new Vector2f(px, py), originScreen, width, height);
                        outsideScreen = true;
                    } else {
                        target = new Vector2f(px, py);
                    }
                }
            }
            Vector2f smooth = outsideScreen ? target : visual.smoothScreenTarget(mc, target);
            if (outsideScreen) visual.resetSmooth(smooth);

            double dx = smooth.x() - originScreen.x();
            double dy = smooth.y() - originScreen.y();
            double screenLength = Math.hypot(dx, dy);
            if (screenLength < 4.0) continue;

            int count = Math.max(1, Math.min(240, (int) (screenLength / 6.0)));
            for (int i = 1; i <= count; i++) {
                double t = (double) i / count;
                int x = (int) Math.round(originScreen.x() + dx * t);
                int y = (int) Math.round(originScreen.y() + dy * t);
                int radius = i == count ? 2 : 1;
                g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, DOT_COLOR);
            }
            drawn = true;
        }
        return drawn;
    }

    /**
     * 终点在屏幕外时，把它收到「起点→终点」这条射线与屏幕矩形边界的交点。
     *
     * <p>直接用越界坐标会让连线被拉长成横穿画面的长线；收边界后，
     * 连线方向仍然指向真实终点，视觉上是「指向屏幕外」。
     */
    /**
     * 目标在相机背后时的连线终点。
     *
     * <p>背对时透视除法的 w 为负，投影会退化（{@code screenPoint} 返回 null），
     * 但目标本身是存在的，不能当成「无目标」。这里取「相机→目标」方向在
     * 屏幕平面上的投影：转到哪个方向，连线就指向那一侧的边缘。
     *
     * <p>用「相机右向量 / 上向量」做点积判断方向，比直接用世界坐标更稳
     * （玩家平移时不会让终点乱跳）。
     */
    private static Vector2f behindTargetEdge(Minecraft mc, Vec3 target,
                                              Vector2f origin, int width, int height) {
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 toTarget = target.subtract(cam.position());
        if (toTarget.lengthSqr() < 1.0E-8) return new Vector2f(width / 2.0F, height);

        // 相机朝向的右向量与上向量（由旋转四元数变换基向量得到，单位长度）
        Quaternionf rot = cam.rotation();
        Vec3 right = new Vec3(rot.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
        Vec3 up = new Vec3(rot.transform(new Vector3f(0.0F, 1.0F, 0.0F)));

        double rx = toTarget.dot(right);
        double uy = toTarget.dot(up);
        if (Double.isNaN(rx) || Double.isNaN(uy)) return new Vector2f(width / 2.0F, height);
        if (Math.abs(rx) < 1.0E-8 && Math.abs(uy) < 1.0E-8) {
            // 方向正对前方或正后方：没有横向分量，连线收到下边缘中点
            return new Vector2f(width / 2.0F, height);
        }

        // 沿着 (rx, uy) 的方向延伸到画面边界
        double dx = rx;
        double dy = -uy;  // 屏幕上 y 向下
        double ox = origin.x();
        double oy = origin.y();
        double t = Double.MAX_VALUE;
        if (dx > 0) t = Math.min(t, (width - ox) / dx);
        if (dx < 0) t = Math.min(t, (0 - ox) / dx);
        if (dy > 0) t = Math.min(t, (height - oy) / dy);
        if (dy < 0) t = Math.min(t, (0 - oy) / dy);
        if (t == Double.MAX_VALUE || t <= 0 || Double.isNaN(t)) {
            return new Vector2f(width / 2.0F, height);
        }
        return new Vector2f((float) (ox + dx * t), (float) (oy + dy * t));
    }

    private static Vector2f clampToScreen(Vector2f target, Vector2f origin, int width, int height) {
        float x = target.x();
        float y = target.y();
        if (x >= 0 && x <= width && y >= 0 && y <= height) return target;

        double dx = x - origin.x();
        double dy = y - origin.y();
        if (dx == 0.0 && dy == 0.0) return target;

        // 与四条边分别求 t，取最先命中的那条
        double t = Double.MAX_VALUE;
        if (dx > 0) t = Math.min(t, (width - origin.x()) / dx);
        if (dx < 0) t = Math.min(t, (0 - origin.x()) / dx);
        if (dy > 0) t = Math.min(t, (height - origin.y()) / dy);
        if (dy < 0) t = Math.min(t, (0 - origin.y()) / dy);
        if (t == Double.MAX_VALUE || t <= 0 || Double.isNaN(t)) {
            return new Vector2f(width / 2.0F, height);
        }
        return new Vector2f((float) (origin.x() + dx * t), (float) (origin.y() + dy * t));
    }

    private Vector2f screenLineStart(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return null;
        double w = mc.getWindow().getGuiScaledWidth();
        double h = mc.getWindow().getGuiScaledHeight();
        int armSign = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;

        double lift = (ANCHOR_LIFT + ANCHOR_EXTRA_LIFT) * FOLD;

        double x = w * 0.5 + armSign * w * 0.18 + w * 0.05 + SCREEN_OFFSET_X * w * FOLD;
        double y = h * 0.72 - lift * h * 0.5 - h * 0.05 - SCREEN_OFFSET_Y * h * FOLD;
        return new Vector2f((float) x, (float) y);
    }

    private void refreshFollow(Visual visual, Minecraft mc) {
        if (!visual.follow) return;
        if (mc.player == null || mc.level == null) return;
        Aim aim = aim(mc, visual.slot.distance);
        visual.refresh(resolveTarget(mc, visual.slot, aim), aim);
    }

    /**
     * 连线终点的世界坐标。
     *
     * @return null 表示这条命令没有对应的世界目标（例如 {@code /time set day} 这类
     *         与位置无关的命令，或目标就是玩家自己），此时连线收到屏幕下边缘中点
     */
    private Vec3 endpoint(Minecraft mc, Visual visual, float partialTick) {
        if (visual.target != null && !visual.target.isRemoved()) {
            // 目标就是玩家自己时同样没有外部目标
            if (visual.target != mc.player) {
                Vec3 pos = visual.target.getPosition(partialTick);
                return pos.add(0.0, visual.target.getBbHeight() * 0.5, 0.0);
            }
            return null;
        }
        if (visual.dotEnd != null && (visual.follow || containsSelector(visual.command, 'f')
                || containsSelector(visual.command, 't'))) return visual.dotEnd;
        return null;
    }

    private Vec3 screenPoint(Minecraft mc, Vec3 world) {
        Camera cam = mc.gameRenderer.mainCamera();
        Matrix4f matrix = cam.getViewRotationProjectionMatrix(new Matrix4f());
        Vec3 camPos = cam.position();
        Vector4f clip = matrix.transform(new Vector4f(
                (float) (world.x - camPos.x),
                (float) (world.y - camPos.y),
                (float) (world.z - camPos.z),
                1.0F));

        if (!Double.isFinite(clip.w) || clip.w <= 0.0F) return null;
        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) return null;
        double w = mc.getWindow().getGuiScaledWidth();
        double h = mc.getWindow().getGuiScaledHeight();
        return new Vec3((ndcX * 0.5 + 0.5) * w, (1.0 - (ndcY * 0.5 + 0.5)) * h, clip.z / clip.w);
    }

    private Visual activeVisual() {
        for (Visual visual : visuals) if (visual.follow) return visual;
        return null;
    }

    private String pulseKey(Entity target, Vec3 anchor) {
        return target != null ? "e:" + target.getUUID() : String.format(Locale.ROOT, "f:%.2f,%.2f,%.2f", anchor.x, anchor.y, anchor.z);
    }

    private static String stripSlash(String command) {
        String value = command == null ? "" : command.strip();
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    private long flashUntilNanos;

    private static final long FLASH_MILLIS = 220L;

    private void triggerFlash() {
        flashUntilNanos = System.nanoTime() + FLASH_MILLIS * 1_000_000L;
    }

    float flashStrength() {
        if (flashUntilNanos <= 0L) return 0.0F;
        long remain = flashUntilNanos - System.nanoTime();
        if (remain <= 0L) {
            flashUntilNanos = 0L;
            return 0.0F;
        }
        return (float) (remain / (FLASH_MILLIS * 1_000_000L));
    }

    private void send(Minecraft mc, String command) {
        if (mc.player != null && mc.getConnection() != null && command != null && !command.isBlank()) {

            if (isFillCommand(command)) recentFillAt = mc.level != null ? (int) (mc.level.getGameTime() & 0xFFFFFF) : 0;
            mc.player.connection.sendCommand(command);
        }
    }

    private static boolean isFillCommand(String command) {
        int i = 0;
        while (i < command.length() && command.charAt(i) == ' ') i++;
        if (!command.regionMatches(true, i, "fill", 0, 4)) return false;
        int next = i + 4;
        return next >= command.length() || command.charAt(next) == ' ';
    }

    static boolean consumeRecentFill() {
        if (recentFillAt < 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            recentFillAt = -1;
            return false;
        }
        int now = (int) (mc.level.getGameTime() & 0xFFFFFF);
        int elapsed = (now - recentFillAt) & 0xFFFFFF;
        if (elapsed > 20) {
            recentFillAt = -1;
            return false;
        }

        recentFillAt = -1;
        return true;
    }
}
