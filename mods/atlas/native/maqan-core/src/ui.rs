//! MAQAN's data-driven UI composition engine.
//!
//! Rust owns layout, visual tokens, component composition and draw-command
//! generation. The Minecraft host remains responsible for executing commands
//! against the active LWJGL/Minecraft render context and for native font glyphs.

use std::fmt;
use crate::motion::{progress as eased_progress, Easing};

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl Rect {
    pub fn new(x: f32, y: f32, width: f32, height: f32) -> Self {
        Self { x, y, width: width.max(0.0), height: height.max(0.0) }
    }

    pub fn right(self) -> f32 { self.x + self.width }
    pub fn bottom(self) -> f32 { self.y + self.height }
    pub fn is_empty(self) -> bool { self.width <= 0.0 || self.height <= 0.0 }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Color(pub u32);

impl Color {
    pub const TRANSPARENT: Self = Self(0x00000000);
    pub const TEXT: Self = Self(0xFFE8EEF5);
    pub const MUTED: Self = Self(0xFF8292A5);
    pub const CYAN: Self = Self(0xFF69CDE2);
    pub const GOLD: Self = Self(0xFFE5BD78);
    pub const BACKGROUND: Self = Self(0xFF080D14);
    pub const PANEL: Self = Self(0xD9101823);
    pub const PANEL_EDGE: Self = Self(0x66566B80);
    pub const BUTTON: Self = Self(0xCC172330);
    pub const BUTTON_HOVER: Self = Self(0xE0253948);
    pub const BUTTON_DISABLED: Self = Self(0x99101822);
}

#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TextRole {
    Heading,
    Body,
    Muted,
    Accent,
    Caption,
}

#[derive(Clone, Debug, PartialEq)]
pub enum DrawCommand {
    Fill { rect: Rect, color: Color },
    Stroke { rect: Rect, color: Color, thickness: f32 },
    Line { x1: f32, y1: f32, x2: f32, y2: f32, color: Color, thickness: f32 },
    Text { x: f32, y: f32, value: String, role: TextRole, shadow: bool },
    PushClip { rect: Rect },
    PopClip,
    Glow { cx: f32, cy: f32, radius: f32, color: Color, intensity: f32 },
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct UiFrame {
    pub width: u32,
    pub height: u32,
    pub scale: f32,
    pub commands: Vec<DrawCommand>,
}

impl UiFrame {
    pub fn new(width: u32, height: u32, scale: f32) -> Self {
        Self { width, height, scale: if scale.is_finite() { scale.max(0.25) } else { 1.0 }, commands: Vec::new() }
    }

    pub fn fill(&mut self, rect: Rect, color: Color) {
        if valid_rect(rect) && !rect.is_empty() { self.commands.push(DrawCommand::Fill { rect, color }); }
    }

    pub fn stroke(&mut self, rect: Rect, color: Color, thickness: f32) {
        if valid_rect(rect) && !rect.is_empty() && thickness.is_finite() && thickness > 0.0 {
            self.commands.push(DrawCommand::Stroke { rect, color, thickness });
        }
    }

    pub fn line(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, color: Color, thickness: f32) {
        if [x1, y1, x2, y2, thickness].iter().all(|v| v.is_finite()) && thickness > 0.0 {
            self.commands.push(DrawCommand::Line { x1, y1, x2, y2, color, thickness });
        }
    }

    pub fn glow(&mut self, cx: f32, cy: f32, radius: f32, color: Color, intensity: f32) {
        if [cx, cy, radius, intensity].iter().all(|v| v.is_finite()) && radius > 0.0 && intensity > 0.0 {
            self.commands.push(DrawCommand::Glow { cx, cy, radius, color, intensity: intensity.clamp(0.0, 1.0) });
        }
    }

    pub fn text(&mut self, x: f32, y: f32, value: impl Into<String>, role: TextRole) {
        if x.is_finite() && y.is_finite() {
            self.commands.push(DrawCommand::Text { x, y, value: value.into(), role, shadow: true });
        }
    }

    pub fn panel(&mut self, rect: Rect, accent: Color) {
        if !valid_rect(rect) || rect.is_empty() { return; }
        self.fill(rect, Color::PANEL);
        self.line(rect.x, rect.y, rect.right(), rect.y, Color::PANEL_EDGE, 1.0);
        self.line(rect.x, rect.bottom() - 1.0, rect.right(), rect.bottom() - 1.0,
            Color(0x333E5265), 1.0);
        let _ = accent; // Accent is reserved for semantic state, not decoration.
    }

    /// Layered, animated edge lighting for glass-like AAA panels.
    pub fn panel_glow(&mut self, rect: Rect, accent: Color, phase: f32) {
        self.panel(rect, accent);
        let _ = phase;
    }

    /// Brief cinematic transition overlay for atlas-level navigation.
    /// Progress is normalized to 0..1; direction 0 sweeps forward, 1 sweeps backward.
    pub fn transition_overlay(&mut self, rect: Rect, progress: f32, direction: u8) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 80.0 || rect.height < 60.0 || direction > 1 { return; }
        let p = eased_progress(progress, Easing::EaseInOutCubic);
        if p >= 1.0 { return; }
        let fade = (1.0 - p) * (1.0 - p);
        let alpha = (fade * 22.0).round() as u32;
        if alpha > 0 { self.fill(rect, Color((alpha << 24) | 0x00101C2A)); }
        let travel = if direction == 0 { p } else { 1.0 - p };
        let x = rect.x + rect.width * travel;
        let line_alpha = (fade * 170.0).round() as u32;
        self.fill(Rect::new(x - 2.0, rect.y + 8.0, 1.0, (rect.height - 16.0).max(0.0)), Color((line_alpha / 3 << 24) | 0x0079D9F2));
        self.fill(Rect::new(x, rect.y + 8.0, 1.0, (rect.height - 16.0).max(0.0)), Color((line_alpha << 24) | 0x0079D9F2));
        self.fill(Rect::new(x + 1.0, rect.y + 8.0, 2.0, (rect.height - 16.0).max(0.0)), Color((line_alpha / 4 << 24) | 0x0046A6C4));
        self.glow(x, rect.y + rect.height * 0.5, (rect.height * 0.22).clamp(24.0, 110.0), Color::CYAN, 0.10 * fade);
        let corner = Color((((fade * 110.0).round() as u32) << 24) | 0x0079D9F2);
        let len = 22.0_f32.min(rect.width * 0.08).min(rect.height * 0.16);
        self.line(rect.x + 8.0, rect.y + 8.0, rect.x + 8.0 + len, rect.y + 8.0, corner, 1.0);
        self.line(rect.right() - 8.0 - len, rect.bottom() - 8.0, rect.right() - 8.0, rect.bottom() - 8.0, corner, 1.0);
    }

    /// Context menu surface. Labels, available actions and pointer handling stay in Java.
    pub fn context_menu(&mut self, rect: Rect, phase: f32, hovered_row: i32, opacity: f32) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 100.0 || rect.height < 70.0 { return; }
        let alpha = if opacity.is_finite() { opacity.clamp(0.0, 1.0) } else { 1.0 };
        let tint = |color: Color| Color((((((color.0 >> 24) & 255) as f32 * alpha) as u32) << 24) | (color.0 & 0x00FF_FFFF));
        self.fill(rect, tint(Color(0xF00A1424)));
        self.stroke(rect, tint(Color(0xAA54758C)), 1.0);
        self.fill(Rect::new(rect.x + 1.0, rect.y + 1.0, (rect.width - 2.0).max(0.0), 1.0), tint(Color::CYAN));
        self.fill(Rect::new(rect.x + 1.0, rect.bottom() - 1.0, (rect.width - 2.0).max(0.0), 1.0), tint(Color(0x55263E50)));
        let _ = phase;
        for row in 0..4 {
            let y = rect.y + 20.0 + row as f32 * 18.0;
            let row_rect = Rect::new(rect.x + 4.0, y, rect.width - 8.0, 17.0);
            if hovered_row == row {
                self.fill(row_rect, tint(Color(0x502A5064)));
                self.fill(Rect::new(row_rect.x, row_rect.y + 2.0, 2.0, 13.0), tint(Color::CYAN));
            }
            if row < 3 {
                self.line(rect.x + 9.0, y + 17.0, rect.right() - 9.0, y + 17.0, tint(Color(0x223D6076)), 1.0);
            }
        }
        self.line(rect.x + 8.0, rect.y + 18.0, rect.right() - 8.0, rect.y + 18.0, tint(Color(0x665D7895)), 1.0);
    }

    /// Hierarchical breadcrumb chip. Navigation and hit-testing remain owned by Java.
    pub fn breadcrumb_chip(&mut self, rect: Rect, _phase: f32, state: u8) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 14.0 || rect.height < 8.0 { return; }
        let current = state == 2;
        let hovered = state == 1;
        let base = if current { Color(0x50203A49) } else if hovered { Color(0x301B2C39) } else { Color(0x180F1B26) };
        let edge = if current || hovered { Color(0x66566B80) } else { Color(0x224D6579) };
        self.fill(rect, base);
        self.stroke(rect, edge, 1.0);
        self.fill(Rect::new(rect.x + 1.0, rect.y + 1.0, (rect.width - 2.0).max(0.0), 1.0),
            if current { Color(0x8869CDE2) } else { Color(0x224D6579) });
        if current {
            self.fill(Rect::new(rect.x + 4.0, rect.bottom() - 2.0, (rect.width - 8.0).max(0.0), 1.0),
                Color(0xCC69CDE2));
        } else if hovered {
            self.line(rect.x + 5.0, rect.bottom() - 2.0, rect.right() - 5.0, rect.bottom() - 2.0, Color(0x8869CDE2), 1.0);
        }
    }

    /// Tab surface with animated active signal and restrained hover feedback.
    pub fn navigation_tab(&mut self, rect: Rect, _phase: f32, state: u8) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 12.0 || rect.height < 10.0 { return; }
        let active = state == 2;
        let hovered = state == 1;
        if active {
            self.fill(Rect::new(rect.x, rect.bottom() - 2.0, rect.width, 2.0), Color::CYAN);
        } else if hovered {
            self.fill(Rect::new(rect.x + 6.0, rect.bottom() - 1.0,
                (rect.width - 12.0).max(0.0), 1.0), Color(0x8879AFC0));
        }
    }

    /// Compact catalogue entry card with distinct idle, hover and selected states.
    pub fn catalogue_card(&mut self, rect: Rect, _phase: f32, state: u8) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 28.0 || rect.height < 12.0 { return; }
        let selected = state == 2;
        let hovered = state == 1;
        if selected {
            self.fill(rect, Color(0x351C3B4A));
            self.fill(Rect::new(rect.x, rect.y + 3.0, 2.0, (rect.height - 6.0).max(0.0)), Color::CYAN);
        } else if hovered {
            self.fill(rect, Color(0x201E3040));
        }
    }

    /// Compact dossier card used by the selected celestial-body inspector.
    /// It decorates the information header without owning its text or controls.
    pub fn dossier_card(&mut self, rect: Rect, _phase: f32, selected: bool) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 32.0 || rect.height < 24.0 { return; }
        self.fill(rect, Color(0x99101823));
        self.line(rect.x, rect.y, rect.right(), rect.y, Color(0x44566B80), 1.0);
        if selected {
            self.fill(Rect::new(rect.x, rect.y + 4.0, 2.0, (rect.height - 8.0).max(0.0)), Color::CYAN);
        }
    }

    /// Reusable section heading with a restrained animated accent rule.
    pub fn section_header(&mut self, rect: Rect, label: impl Into<String>, label_width: f32, phase: f32) {
        if !valid_rect(rect) || rect.is_empty() || rect.width < 12.0 { return; }
        let label_width = label_width.max(0.0).min((rect.width - 12.0).max(0.0));
        let _ = phase;
        self.text(rect.x, rect.y, label, TextRole::Accent);
        let line_y = rect.y + 13.0;
        self.line(rect.x, line_y, rect.right(), line_y, Color(0x443D5265), 1.0);
        let accent_end = (rect.x + 6.0 + label_width).min(rect.right());
        self.line(rect.x, line_y, accent_end, line_y, Color::CYAN, 1.0);
    }

    pub fn button_glow(&mut self, rect: Rect, label: impl Into<String>, state: ButtonState, phase: f32, label_width: f32) {
        if !valid_rect(rect) || rect.is_empty() { return; }
        let (background, edge, role) = match state {
            ButtonState::Normal => (Color::BUTTON, Color::PANEL_EDGE, TextRole::Body),
            ButtonState::Hovered => (Color::BUTTON_HOVER, Color::CYAN, TextRole::Heading),
            ButtonState::Disabled => (Color::BUTTON_DISABLED, Color::TRANSPARENT, TextRole::Muted),
        };
        self.fill(rect, background);
        if state == ButtonState::Hovered {
            self.fill(Rect::new(rect.x, rect.y, 2.0, rect.height), Color::CYAN);
        } else if edge != Color::TRANSPARENT {
            self.line(rect.x, rect.y, rect.right(), rect.y, Color(0x44566B80), 1.0);
        }
        let _ = phase;
        let centered_x = rect.x + ((rect.width - label_width.max(0.0).min(rect.width)) * 0.5);
        self.text(centered_x, rect.y + (rect.height - 9.0) * 0.5, label, role);
    }

    pub fn button(&mut self, rect: Rect, label: impl Into<String>, state: ButtonState) {
        if !valid_rect(rect) || rect.is_empty() { return; }
        let (background, edge, role) = match state {
            ButtonState::Normal => (Color::BUTTON, Color::PANEL_EDGE, TextRole::Body),
            ButtonState::Hovered => (Color::BUTTON_HOVER, Color::CYAN, TextRole::Heading),
            ButtonState::Disabled => (Color::BUTTON_DISABLED, Color::TRANSPARENT, TextRole::Muted),
        };
        self.fill(rect, background);
        if state == ButtonState::Hovered {
            self.fill(Rect::new(rect.x, rect.y, 2.0, rect.height), Color::CYAN);
        } else if edge != Color::TRANSPARENT {
            self.line(rect.x, rect.y, rect.right(), rect.y, Color(0x44566B80), 1.0);
        }
        self.text(rect.x + 9.0, rect.y + (rect.height - 9.0) * 0.5, label, role);
    }

    /// A compact, versioned, little-endian packet for one JNI crossing per frame.
    /// Strings are UTF-8 and length-prefixed; Java validates every boundary.
    pub fn encode(&self) -> Result<Vec<u8>, PacketError> {
        let mut out = Vec::with_capacity(16 + self.commands.len() * 32);
        out.extend_from_slice(b"MQUI");
        put_u16(&mut out, 1);
        put_u16(&mut out, 0);
        put_u32(&mut out, self.width);
        put_u32(&mut out, self.height);
        put_f32(&mut out, self.scale);
        put_u32(&mut out, u32::try_from(self.commands.len()).map_err(|_| PacketError::TooManyCommands)?);
        for command in &self.commands {
            match command {
                DrawCommand::Fill { rect, color } => {
                    out.push(1); put_rect(&mut out, *rect); put_u32(&mut out, color.0);
                }
                DrawCommand::Stroke { rect, color, thickness } => {
                    out.push(2); put_rect(&mut out, *rect); put_u32(&mut out, color.0); put_f32(&mut out, *thickness);
                }
                DrawCommand::Line { x1, y1, x2, y2, color, thickness } => {
                    out.push(3);
                    for value in [x1, y1, x2, y2] { put_f32(&mut out, *value); }
                    put_u32(&mut out, color.0); put_f32(&mut out, *thickness);
                }
                DrawCommand::Text { x, y, value, role, shadow } => {
                    let bytes = value.as_bytes();
                    let len = u32::try_from(bytes.len()).map_err(|_| PacketError::StringTooLong)?;
                    out.push(4); put_f32(&mut out, *x); put_f32(&mut out, *y);
                    out.push(*role as u8); out.push(u8::from(*shadow)); put_u32(&mut out, len);
                    out.extend_from_slice(bytes);
                }
                DrawCommand::PushClip { rect } => { out.push(5); put_rect(&mut out, *rect); }
                DrawCommand::PopClip => out.push(6),
                DrawCommand::Glow { cx, cy, radius, color, intensity } => {
                    out.push(7); put_f32(&mut out, *cx); put_f32(&mut out, *cy);
                    put_f32(&mut out, *radius); put_u32(&mut out, color.0); put_f32(&mut out, *intensity);
                }
            }
        }
        Ok(out)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ButtonState { Normal, Hovered, Disabled }

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PacketError { TooManyCommands, StringTooLong }

impl fmt::Display for PacketError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { write!(f, "{self:?}") }
}

fn valid_rect(rect: Rect) -> bool {
    [rect.x, rect.y, rect.width, rect.height].iter().all(|v| v.is_finite())
}

fn put_u16(out: &mut Vec<u8>, value: u16) { out.extend_from_slice(&value.to_le_bytes()); }
fn put_u32(out: &mut Vec<u8>, value: u32) { out.extend_from_slice(&value.to_le_bytes()); }
fn put_f32(out: &mut Vec<u8>, value: f32) { out.extend_from_slice(&value.to_le_bytes()); }
fn put_rect(out: &mut Vec<u8>, rect: Rect) {
    for value in [rect.x, rect.y, rect.width, rect.height] { put_f32(out, value); }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ignores_empty_and_invalid_geometry() {
        let mut frame = UiFrame::new(800, 600, 1.0);
        frame.fill(Rect::new(0.0, 0.0, 0.0, 10.0), Color::TEXT);
        frame.line(0.0, f32::NAN, 10.0, 10.0, Color::CYAN, 1.0);
        frame.stroke(Rect::new(0.0, 0.0, 10.0, 10.0), Color::TEXT, f32::INFINITY);
        assert!(frame.commands.is_empty());
    }

    #[test]
    fn rejects_non_finite_rectangles() {
        let mut frame = UiFrame::new(800, 600, 1.0);
        frame.fill(Rect::new(f32::NAN, 0.0, 10.0, 10.0), Color::TEXT);
        frame.panel(Rect::new(0.0, 0.0, f32::INFINITY, 10.0), Color::CYAN);
        frame.button(Rect::new(0.0, 0.0, 10.0, f32::NAN), "BAD", ButtonState::Normal);
        assert!(frame.commands.is_empty());
    }

    #[test]
    fn transition_overlay_moves_and_fades_without_persisting() {
        let rect = Rect::new(0.0, 0.0, 800.0, 480.0);
        let mut early = UiFrame::new(800, 480, 1.0);
        let mut late = UiFrame::new(800, 480, 1.0);
        let mut complete = UiFrame::new(800, 480, 1.0);
        early.transition_overlay(rect, 0.15, 0);
        late.transition_overlay(rect, 0.75, 0);
        complete.transition_overlay(rect, 1.0, 0);
        assert_ne!(early.commands, late.commands);
        assert!(!early.commands.is_empty());
        assert!(complete.commands.is_empty());
    }

    #[test]
    fn transition_overlay_rejects_invalid_direction_and_bounds() {
        let mut frame = UiFrame::new(320, 200, 1.0);
        frame.transition_overlay(Rect::new(0.0, 0.0, 20.0, 20.0), 0.2, 0);
        frame.transition_overlay(Rect::new(0.0, 0.0, 320.0, 200.0), 0.2, 2);
        assert!(frame.commands.is_empty());
    }

    #[test]
    fn context_menu_highlights_only_the_hovered_action_row() {
        let rect = Rect::new(12.0, 16.0, 178.0, 100.0);
        let mut idle = UiFrame::new(240, 160, 1.0);
        let mut hovered = UiFrame::new(240, 160, 1.0);
        idle.context_menu(rect, 0.0, -1, 1.0);
        hovered.context_menu(rect, 0.0, 2, 1.0);
        assert_ne!(idle.commands, hovered.commands);
        assert!(!hovered.commands.iter().any(|c| matches!(c, DrawCommand::Glow { .. })));
    }

    #[test]
    fn context_menu_rejects_invalid_bounds_and_clamps_opacity() {
        let mut frame = UiFrame::new(240, 160, 1.0);
        frame.context_menu(Rect::new(0.0, 0.0, 20.0, 20.0), 0.0, 0, 1.0);
        assert!(frame.commands.is_empty());
        frame.context_menu(Rect::new(12.0, 16.0, 178.0, 100.0), 0.0, -1, f32::NAN);
        assert!(!frame.commands.is_empty());
    }

    #[test]
    fn breadcrumb_chip_distinguishes_current_hover_and_idle() {
        let rect = Rect::new(4.0, 4.0, 90.0, 14.0);
        let mut idle = UiFrame::new(120, 30, 1.0);
        let mut hover = UiFrame::new(120, 30, 1.0);
        let mut current = UiFrame::new(120, 30, 1.0);
        idle.breadcrumb_chip(rect, 0.0, 0);
        hover.breadcrumb_chip(rect, 0.0, 1);
        current.breadcrumb_chip(rect, 0.0, 2);
        assert_ne!(idle.commands, hover.commands);
        assert_ne!(hover.commands, current.commands);
        assert!(!current.commands.iter().any(|c| matches!(c, DrawCommand::Glow { .. })));
    }

    #[test]
    fn navigation_tab_has_three_visual_states() {
        let rect = Rect::new(4.0, 4.0, 80.0, 18.0);
        let mut idle = UiFrame::new(100, 40, 1.0);
        let mut hover = UiFrame::new(100, 40, 1.0);
        let mut active = UiFrame::new(100, 40, 1.0);
        idle.navigation_tab(rect, 0.0, 0);
        hover.navigation_tab(rect, 0.0, 1);
        active.navigation_tab(rect, 0.0, 2);
        assert_ne!(idle.commands, hover.commands);
        assert_ne!(hover.commands, active.commands);
        assert!(active.commands.iter().any(|c| matches!(c, DrawCommand::Fill { color: Color::CYAN, .. })));
    }

    #[test]
    fn catalogue_card_has_distinct_selection_and_hover_states() {
        let rect = Rect::new(8.0, 12.0, 220.0, 29.0);
        let mut idle = UiFrame::new(320, 240, 1.0);
        let mut hovered = UiFrame::new(320, 240, 1.0);
        let mut selected = UiFrame::new(320, 240, 1.0);
        idle.catalogue_card(rect, 0.0, 0);
        hovered.catalogue_card(rect, 0.0, 1);
        selected.catalogue_card(rect, 0.0, 2);
        assert_ne!(idle.commands, hovered.commands);
        assert_ne!(hovered.commands, selected.commands);
        assert!(selected.commands.iter().any(|c| matches!(c, DrawCommand::Fill { color: Color::CYAN, .. })));
    }

    #[test]
    fn dossier_card_emits_surface_and_selection_signal() {
        let mut frame = UiFrame::new(640, 480, 1.0);
        frame.dossier_card(Rect::new(20.0, 30.0, 260.0, 64.0), 1.25, true);
        assert!(frame.commands.iter().any(|c| matches!(c, DrawCommand::Fill { .. })));
        assert!(frame.commands.iter().any(|c| matches!(c, DrawCommand::Line { .. })));
        assert!(!frame.commands.iter().any(|c| matches!(c, DrawCommand::Glow { .. })));
    }

    #[test]
    fn dossier_card_rejects_too_small_or_invalid_bounds() {
        let mut frame = UiFrame::new(640, 480, 1.0);
        frame.dossier_card(Rect::new(0.0, 0.0, 20.0, 80.0), 0.0, true);
        frame.dossier_card(Rect::new(f32::NAN, 0.0, 200.0, 80.0), 0.0, true);
        assert!(frame.commands.is_empty());
    }

    #[test]
    fn section_header_emits_label_and_accent_rule() {
        let mut frame = UiFrame::new(800, 600, 1.0);
        frame.section_header(Rect::new(40.0, 80.0, 320.0, 18.0), "ORIGIN", 42.0, 1.0);
        assert!(matches!(frame.commands.first(), Some(DrawCommand::Text { value, role: TextRole::Accent, .. }) if value == "ORIGIN"));
        assert!(frame.commands.iter().any(|command| matches!(command, DrawCommand::Line { .. })));
    }

    #[test]
    fn panel_and_button_emit_minimal_surfaces() {
        let mut frame = UiFrame::new(800, 600, 1.0);
        frame.panel_glow(Rect::new(20.0, 20.0, 240.0, 180.0), Color::CYAN, 1.0);
        let panel_commands = frame.commands.len();
        frame.button_glow(Rect::new(32.0, 60.0, 120.0, 24.0), "NAVIGATE", ButtonState::Hovered, 1.0, 54.0);
        assert!(panel_commands >= 3);
        assert!(frame.commands.len() >= panel_commands + 3);
        assert!(matches!(frame.commands.last(), Some(DrawCommand::Text { value, .. }) if value == "NAVIGATE"));
    }

    #[test]
    fn composes_panel_and_button_from_shared_tokens() {
        let mut frame = UiFrame::new(1280, 720, 1.0);
        frame.panel(Rect::new(20.0, 20.0, 300.0, 200.0), Color::CYAN);
        frame.button(Rect::new(30.0, 60.0, 120.0, 24.0), "OPEN", ButtonState::Hovered);
        assert!(frame.commands.len() >= 6);
        assert!(matches!(frame.commands.last(), Some(DrawCommand::Text { value, role: TextRole::Heading, .. }) if value == "OPEN"));
    }

    #[test]
    fn encodes_versioned_packet_and_utf8_text() {
        let mut frame = UiFrame::new(640, 480, 1.5);
        frame.text(12.0, 18.0, "ГАЛАКТИКА", TextRole::Heading);
        let packet = frame.encode().unwrap();
        assert_eq!(&packet[0..4], b"MQUI");
        assert_eq!(u16::from_le_bytes([packet[4], packet[5]]), 1);
        assert!(packet.windows("ГАЛАКТИКА".len()).any(|window| window == "ГАЛАКТИКА".as_bytes()));
    }

    #[test]
    fn clamps_scale_and_preserves_frame_dimensions() {
        let frame = UiFrame::new(1920, 1080, f32::NAN);
        assert_eq!(frame.scale, 1.0);
        assert_eq!((frame.width, frame.height), (1920, 1080));
    }
}
