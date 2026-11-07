package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.NavigatorTravelPlanner;
import com.maqan.galacticnavigator.navigation.DynamicSpaceRoutePlanner;
import com.maqan.galacticnavigator.navigation.CosmicSpaceObstacleCatalog;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerMarker;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerMarkerTracker;
import com.maqan.galacticnavigator.navigation.NavigatorTravelPlanner.Drive;
import com.maqan.galacticnavigator.navigation.NavigatorTravelPlanner.Plan;
import com.maqan.galacticnavigator.navigation.NavigatorTravelPlanner.Request;
import com.maqan.galacticnavigator.navigation.NavigatorTravelPlanner.Vector3;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Interactive route calculator. It deliberately does not execute a teleport. */
public final class NavigatorTravelScreen extends Screen {
    private final Screen parent;
    private Drive drive = Drive.HYPERDRIVE;
    private boolean charted;
    private boolean transitLink;
    private EditBox originX, originY, originZ, destinationX, destinationY, destinationZ;
    private EditBox jumpRange, skill, hazard, safetyRadius;
    private Button driveButton, chartButton, linkButton;
    private Plan plan;
    private DynamicSpaceRoutePlanner.Result spaceRoute;
    private String spaceRouteStatus="";
    private int spaceObstacleCount;
    private int bodiesWithoutRadius;
    private String error = "";
    private final List<Button> visualButtons = new ArrayList<>();
    private final List<EditBox> visualFields = new ArrayList<>();

    public NavigatorTravelScreen(Screen parent) {
        super(Component.literal("MAQAN · JOURNEY PLANNER"));
        this.parent = parent;
    }

    @Override protected void init() {
        visualButtons.clear();
        visualFields.clear();
        int cx = width / 2;
        int panelWidth = Math.min(520, width - 24);
        int left = cx - panelWidth / 2;
        int col = Math.max(90, (panelWidth - 36) / 3);
        int gap = 6;
        int y = Math.max(68, height / 2 - 116);

        originX = field(left, y + 26, col, "Origin X", playerCoordinate(0));
        originY = field(left + col + gap, y + 26, col, "Origin Y", playerCoordinate(1));
        originZ = field(left + (col + gap) * 2, y + 26, col, "Origin Z", playerCoordinate(2));
        destinationX = field(left, y + 78, col, "Destination X", "1000");
        destinationY = field(left + col + gap, y + 78, col, "Destination Y", "0");
        destinationZ = field(left + (col + gap) * 2, y + 78, col, "Destination Z", "0");

        int optionY = y + 130;
        int half = (panelWidth - 3 * gap) / 4;
        jumpRange = field(left, optionY + 26, half, "Jump range", "1000");
        skill = field(left + half + gap, optionY + 26, half, "Navigation skill 0-1", "0.65");
        hazard = field(left + (half + gap) * 2, optionY + 26, half, "Hazard 0-1", "0.10");
        safetyRadius = field(left + (half + gap) * 3, optionY + 26, half, "Arrival safety", "24");

        driveButton = addRenderableWidget(Button.builder(Component.literal("DRIVE: " + drive.label),
                b -> cycleDrive()).bounds(left, optionY + 58, 142, 20).build());
        chartButton = addRenderableWidget(Button.builder(Component.literal("CHARTED: OFF"),
                b -> { charted = !charted; refreshOptions(); })
                .bounds(left + 148, optionY + 58, 112, 20).build());
        linkButton = addRenderableWidget(Button.builder(Component.literal("LINK: NO"),
                b -> { transitLink = !transitLink; refreshOptions(); })
                .bounds(left + 266, optionY + 58, 100, 20).build());

        visualButtons.add(addRenderableWidget(Button.builder(Component.literal("CALCULATE ROUTE"), b -> calculate())
                .bounds(left, optionY + 88, panelWidth - 88, 22).build()));
        visualButtons.add(addRenderableWidget(Button.builder(Component.literal("BACK"), b -> onClose())
                .bounds(left + panelWidth - 78, optionY + 88, 78, 22).build()));
        visualButtons.add(driveButton);
        visualButtons.add(chartButton);
        visualButtons.add(linkButton);
    }

    private EditBox field(int x, int y, int w, String hint, String value) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.literal(hint));
        box.setMaxLength(32);
        box.setHint(Component.literal(hint));
        box.setValue(value);
        addRenderableWidget(box);
        visualFields.add(box);
        return box;
    }

    private String playerCoordinate(int axis) {
        if (minecraft == null || minecraft.player == null) return "0";
        double value = axis == 0 ? minecraft.player.getX()
                : axis == 1 ? minecraft.player.getY() : minecraft.player.getZ();
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void cycleDrive() {
        Drive[] values = Drive.values();
        drive = values[(drive.ordinal() + 1) % values.length];
        refreshOptions();
    }

    private void refreshOptions() {
        if (driveButton != null) driveButton.setMessage(Component.literal("DRIVE: " + drive.label.toUpperCase(Locale.ROOT)));
        if (chartButton != null) chartButton.setMessage(Component.literal("CHARTED: " + (charted ? "YES" : "NO")));
        if (linkButton != null) {
            linkButton.visible = drive.requiresLink;
            linkButton.setMessage(Component.literal("LINK: " + (transitLink ? "VERIFIED" : "MISSING")));
        }
    }

    private void calculate() {
        com.maqan.galacticnavigator.navigation.NavigatorRoutePreview.clear();
        try {
            Vector3 from = new Vector3(number(originX), number(originY), number(originZ));
            Vector3 to = new Vector3(number(destinationX), number(destinationY), number(destinationZ));
            Request request = new Request(from, to, drive, number(jumpRange), number(skill),
                    charted, transitLink, number(hazard), number(safetyRadius));
            plan = NavigatorTravelPlanner.calculate(request);
            spaceRoute=null;
            spaceRouteStatus="";
            spaceObstacleCount=0;
            bodiesWithoutRadius=0;
            if(plan.valid()&&(drive==Drive.SUBLIGHT||drive==Drive.WARP)&&minecraft!=null&&minecraft.level!=null
                    &&parent instanceof NavigatorScreen navigator&&plan.totalDistance()>0.0
                    &&plan.durationSeconds()>0.0){
                String systemId=navigator.selectedUniverseId();
                NavigatorPlayerMarker marker=NavigatorPlayerMarkerTracker.current();
                boolean originMatchesPlayer=marker!=null&&marker.positionKnown()
                        &&Math.sqrt(Math.pow(marker.systemPosition().x()-from.x(),2)
                        +Math.pow(marker.systemPosition().y()-from.y(),2)
                        +Math.pow(marker.systemPosition().z()-from.z(),2))<=2.0;
                if(originMatchesPlayer){
                    // The route geometry uses a constant effective speed whose total
                    // duration matches the drive-specific acceleration/braking estimate.
                    double averageSpeed=plan.totalDistance()/plan.durationSeconds();
                    var snapshot=CosmicSpaceObstacleCatalog.snapshot(systemId,
                            marker.sampledAtSeconds());
                    spaceObstacleCount=snapshot.obstacles().size();
                    bodiesWithoutRadius=snapshot.bodiesWithoutPhysicalRadius();
                    var dynamicRequest=CosmicSpaceObstacleCatalog.requestFromPlayer(
                            systemId,minecraft.level,
                            new DynamicSpaceRoutePlanner.Vec3(plan.calculatedArrival().x(),
                                    plan.calculatedArrival().y(),plan.calculatedArrival().z()),
                            averageSpeed,number(safetyRadius),96,10,8,snapshot);
                    if(dynamicRequest.isPresent()){
                        spaceRoute=DynamicSpaceRoutePlanner.plan(dynamicRequest.get());
                        spaceRouteStatus=spaceRoute.status();
                        com.maqan.galacticnavigator.navigation.NavigatorRoutePreview.publish(
                                systemId,spaceRoute,marker.sampledAtSeconds(),
                                snapshot,dynamicRequest.get());
                    }else{
                        spaceRouteStatus="Live system-space frame could not be verified for this route.";
                    }
                }else{
                    spaceRouteStatus="Collision check requires the origin to match your live player marker.";
                }
            }else if(plan.valid()&&(drive==Drive.HYPERDRIVE||drive==Drive.NAVIGATOR)
                    &&minecraft!=null&&minecraft.level!=null
                    &&parent instanceof NavigatorScreen navigator){
                String systemId=navigator.selectedUniverseId();
                NavigatorPlayerMarker marker=NavigatorPlayerMarkerTracker.current();
                boolean originMatchesPlayer=marker!=null&&marker.positionKnown()
                        &&Math.sqrt(Math.pow(marker.systemPosition().x()-from.x(),2)
                        +Math.pow(marker.systemPosition().y()-from.y(),2)
                        +Math.pow(marker.systemPosition().z()-from.z(),2))<=2.0;
                if(originMatchesPlayer){
                    var snapshot=CosmicSpaceObstacleCatalog.snapshot(systemId,marker.sampledAtSeconds());
                    var mode=drive==Drive.HYPERDRIVE
                            ?com.maqan.galacticnavigator.navigation.TransitWindowPlanner.Mode.HYPERDRIVE
                            :com.maqan.galacticnavigator.navigation.TransitWindowPlanner.Mode.CALCULATED_FOLD;
                    var endpointRequest=new com.maqan.galacticnavigator.navigation.TransitWindowPlanner.Request(
                            mode,new DynamicSpaceRoutePlanner.Vec3(from.x(),from.y(),from.z()),
                            new DynamicSpaceRoutePlanner.Vec3(plan.calculatedArrival().x(),
                                    plan.calculatedArrival().y(),plan.calculatedArrival().z()),
                            marker.sampledAtSeconds(),plan.durationSeconds(),number(safetyRadius),
                            snapshot.obstacles(),null);
                    var endpointCheck=com.maqan.galacticnavigator.navigation.TransitWindowPlanner.evaluate(endpointRequest);
                    spaceRouteStatus=endpointCheck.status();
                    spaceObstacleCount=snapshot.obstacles().size();
                    bodiesWithoutRadius=snapshot.bodiesWithoutPhysicalRadius();
                }else{
                    spaceRouteStatus="Entry/exit hazard check requires the origin to match your live player marker.";
                }
            }else if(plan.valid()&&drive.requiresLink){
                spaceRouteStatus="A discovered link with verified entry/exit coordinates and availability data is required. "
                        +"The manual LINK toggle is not a physical endpoint verification.";
            }else if(plan.valid()){
                spaceRouteStatus="Dynamic obstacle check requires a live player and non-zero travel duration.";
            }
            error = "";
        } catch (Exception ex) {
            plan = null;
            error = "Invalid input. Use finite numbers; skill and hazard must be 0–1.";
        }
    }

    private static double number(EditBox box) {
        double value = Double.parseDouble(box.getValue().trim());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite value");
        return value;
    }

    @Override public void onClose() {
        if (minecraft != null) MaqanScreenHost.back(minecraft, parent);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        MaqanUiTheme.backdrop(g, width, height);
        int panelWidth = Math.min(520, width - 24);
        int left = (width - panelWidth) / 2;
        int y = Math.max(68, height / 2 - 116);
        int optionY = y + 130;
        MaqanUiTheme.panel(g, left - 10, y - 34, left + panelWidth + 10,
                Math.min(height - 8, y + 330));
        MaqanUiTheme.title(g, font, "JOURNEY PLANNER", "Route calculation · data-driven navigation", width);
        MaqanUiTheme.sectionHeader(g, font, "ORIGIN  /  CURRENT POSITION OR CUSTOM COORDINATES", left, y + 8, panelWidth, 18);
        MaqanUiTheme.sectionHeader(g, font, "DESTINATION  /  TARGET MAP COORDINATES", left, y + 60, panelWidth, 18);
        MaqanUiTheme.sectionHeader(g, font, "DRIVE PARAMETERS", left, optionY + 8, panelWidth, 18);
        super.render(g, mouseX, mouseY, partial);
        for (Button button : visualButtons) MaqanUiTheme.button(g, font, button);
        for (EditBox field : visualFields) MaqanUiTheme.inputFrame(g, field);

        int resultY = optionY + 122;
        if (!error.isEmpty()) {
            g.drawString(font, error, left, resultY, 0xFFFF7777, false);
        } else if (plan != null) {
            int color = plan.valid() ? 0xFFB9E9D2 : 0xFFFF7777;
            g.drawString(font, plan.valid() ? "ROUTE SOLUTION" : "ROUTE UNAVAILABLE", left, resultY, color, false);
            g.drawString(font, plan.message(), left, resultY + 14, 0xFFE5EDF5, false);
            if (plan.valid()) {
                g.drawString(font, "Travel time: " + plan.durationLabel()
                        + "   Legs: " + plan.legs().size(), left, resultY + 28, 0xFFE5EDF5, false);
                g.drawString(font, String.format(Locale.ROOT, "Confidence: %.0f%%   Risk: %.0f%%",
                        plan.confidence() * 100, plan.risk() * 100), left, resultY + 42, 0xFFE5EDF5, false);
                g.drawString(font, "Calculated arrival: " + plan.calculatedArrival().compact(),
                        left, resultY + 56, 0xFF67D8F2, false);
                g.drawString(font, String.format(Locale.ROOT, "Arrival uncertainty radius: %.2f",
                        plan.arrivalErrorRadius()), left, resultY + 70, 0xFF9CB0C2, false);
                int lineY = resultY + 88;
                if(spaceRoute!=null){
                    int routeColor=spaceRoute.clear()?0xFF8BE0B5:0xFFFF7777;
                    g.drawString(font,"COSMIC COLLISION CHECK · "+spaceObstacleCount
                            +" EXPLICIT OBSTACLES",left,lineY,0xFF67D8F2,false);
                    lineY+=12;
                    g.drawString(font,spaceRoute.status(),left,lineY,routeColor,false);
                    lineY+=12;
                    if(spaceRoute.clear()){
                        g.drawString(font,String.format(Locale.ROOT,
                                "Clear against known bodies · %.1f units · %d detours · %.0f sec",
                                spaceRoute.distance(),spaceRoute.replans(),spaceRoute.durationSeconds()),
                                left,lineY,0xFFB9E9D2,false);
                    }else{
                        g.drawString(font,"DO NOT EXECUTE · route is not collision-free",
                                left,lineY,0xFFFF7777,false);
                    }
                    lineY+=14;
                    g.drawString(font,"3D ROUTE PREVIEW SAVED · close planner to inspect in the atlas",
                            left,lineY,0xFF67D8F2,false);
                    lineY+=12;
                    if(bodiesWithoutRadius>0||spaceObstacleCount==0){
                        g.drawString(font,spaceObstacleCount==0
                                ?"NO EXPLICIT COLLISION ENVELOPES · NOT A SAFETY CERTIFICATION"
                                :bodiesWithoutRadius+" bodies lack physical radii · coverage incomplete",
                                left,lineY,0xFFFFC66D,false);
                        lineY+=12;
                        g.drawString(font,"Do not treat this result as clearance for automatic execution.",
                                left,lineY,0xFFFFC66D,false);
                        lineY+=12;
                    }
                }else if(!spaceRouteStatus.isBlank()){
                    g.drawString(font,"COSMIC CHECK · "+spaceRouteStatus,left,lineY,0xFFFFC66D,false);
                    lineY+=14;
                }
                for (var leg : plan.legs().stream().limit(5).toList()) {
                    g.drawString(font, "#" + leg.index() + " " + leg.operation()
                            + " · " + String.format(Locale.ROOT, "%.1f units / %.0f sec",
                            leg.distance(), leg.durationSeconds()), left, lineY, 0xFFD1DCE8, false);
                    lineY += 12;
                }
                if (plan.legs().size() > 5)
                    g.drawString(font, "+ " + (plan.legs().size() - 5) + " more legs",
                            left, lineY, 0xFF9CB0C2, false);
            }
        } else {
            g.drawString(font, "Choose a drive model and calculate a route.", left, resultY, 0xFF9CB0C2, false);
        }
        g.drawCenteredString(font, "Calculation only · no teleport or dimension creation is performed",
                width / 2, height - 18, 0xFF8193A8);
    }
}
