//! Responsive horizontal layout for the Navigator's primary workspace panels.

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PanelLayout {
    pub map_top: i32,
    pub map_bottom: i32,
    pub list_left: i32,
    pub list_right: i32,
    pub info_left: i32,
    pub info_right: i32,
    pub map_left: i32,
    pub map_right: i32,
}

/// Computes the main atlas columns. Order uses 0=list, 1=info, 2=map.
pub fn compute_panel_layout(width: i32, height: i32, show_list: bool, show_info: bool, preferred_list: i32, preferred_info: i32, order: &[u8]) -> Option<PanelLayout> {
    if width < 1 || height < 1 || width > 16_384 || height > 16_384 { return None; }
    let gap = 8_i32;
    let footer_reserve = if width < 560 { 60 } else { 36 };
    let top = 76_i32.max((height as f32 * 0.34).round() as i32).min(94);
    let bottom = (height - footer_reserve).max(top + 52).min(height - 8);
    let mut result = PanelLayout { map_top: top, map_bottom: bottom, ..PanelLayout::default() };

    let mut left_width = if show_list && width >= 620 { preferred_list.clamp(140, 340).min((width - 24) / 3) } else { 0 };
    let mut center_width = if show_info && width >= 850 { preferred_info.clamp(180, 420).min((width - 24) * 2 / 5) } else { 0 };
    let mut active_panels = 1 + (left_width > 0) as i32 + (center_width > 0) as i32;
    let mut available = (width - 24 - gap * (active_panels - 1)).max(1);
    let mut map_minimum = available.min(if width < 620 { 120 } else { 180 });
    while left_width + center_width + map_minimum > available {
        if center_width > 0 { center_width = 0; }
        else if left_width > 0 { left_width = 0; }
        else { break; }
        active_panels = 1 + (left_width > 0) as i32 + (center_width > 0) as i32;
        available = (width - 24 - gap * (active_panels - 1)).max(1);
        map_minimum = available.min(if width < 620 { 120 } else { 180 });
    }
    let map_width = (available - left_width - center_width).max(1);
    let widths = [left_width, center_width, map_width];
    let mut cursor = 12_i32;
    let mut first = true;
    let normalized_order = normalize_panel_order(order);
    let mut seen = [false; 3];
    for &id in normalized_order.iter() {
        if id > 2 || seen[id as usize] { continue; }
        seen[id as usize] = true;
        let panel_width = widths[id as usize];
        if panel_width <= 0 { continue; }
        if !first { cursor += gap; }
        match id {
            0 => { result.list_left = cursor; result.list_right = cursor + panel_width; }
            1 => { result.info_left = cursor; result.info_right = cursor + panel_width; }
            2 => { result.map_left = cursor; result.map_right = cursor + panel_width; }
            _ => {}
        }
        cursor += panel_width;
        first = false;
    }
    if result.map_right <= result.map_left {
        result.map_left = 12;
        result.map_right = (width - 12).max(13);
    }
    Some(result)
}


/// Canonicalizes persisted/native panel order: valid unique entries keep their
/// relative order and missing/unknown entries are appended in stable default order.
pub fn normalize_panel_order(order: &[u8]) -> [u8; 3] {
    let mut result = [0_u8; 3];
    let mut seen = [false; 3];
    let mut count = 0_usize;
    for &id in order {
        if id <= 2 && !seen[id as usize] && count < 3 {
            result[count] = id;
            seen[id as usize] = true;
            count += 1;
        }
    }
    for id in 0..=2 {
        if !seen[id] && count < 3 {
            result[count] = id as u8;
            count += 1;
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lays_out_all_panels_in_user_order() {
        let layout = compute_panel_layout(1600, 900, true, true, 220, 280, &[1, 2, 0]).unwrap();
        assert!(layout.info_left < layout.map_left);
        assert!(layout.map_left < layout.list_left);
        assert_eq!((layout.map_top, layout.map_bottom), (94, 864));
        assert_eq!((layout.info_left, layout.info_right), (12, 292));
        assert_eq!((layout.map_left, layout.map_right), (300, 1360));
        assert_eq!((layout.list_left, layout.list_right), (1368, 1588));
        assert!(layout.list_right <= 1588);
        assert!(layout.map_top < layout.map_bottom);
    }

    #[test]
    fn hides_optional_panels_on_narrow_viewports() {
        let layout = compute_panel_layout(480, 720, true, true, 220, 280, &[0, 1, 2]).unwrap();
        assert_eq!((layout.list_left, layout.list_right), (0, 0));
        assert_eq!((layout.info_left, layout.info_right), (0, 0));
        assert!(layout.map_right > layout.map_left);
    }

    #[test]
    fn corrupt_order_keeps_the_map_visible() {
        let layout = compute_panel_layout(1200, 800, true, true, 220, 280, &[0, 0, 99]).unwrap();
        assert!(layout.map_right > layout.map_left);
        // Missing/duplicate IDs are repaired rather than silently dropping a panel.
        assert!(layout.info_right > layout.info_left);
    }

    #[test]
    fn respects_user_panel_width_preferences() {
        let narrow = compute_panel_layout(1600, 900, true, true, 150, 200, &[0, 1, 2]).unwrap();
        let wide = compute_panel_layout(1600, 900, true, true, 300, 380, &[0, 1, 2]).unwrap();
        assert_eq!(narrow.list_right - narrow.list_left, 150);
        assert_eq!(narrow.info_right - narrow.info_left, 200);
        assert_eq!(wide.list_right - wide.list_left, 300);
        assert_eq!(wide.info_right - wide.info_left, 380);
    }

    #[test]
    fn clamps_panel_width_preferences() {
        let layout = compute_panel_layout(1600, 900, true, true, 10, 900, &[0, 1, 2]).unwrap();
        assert_eq!(layout.list_right - layout.list_left, 140);
        assert_eq!(layout.info_right - layout.info_left, 420);
    }

    #[test]
    fn normalizes_duplicate_unknown_and_missing_panel_ids() {
        assert_eq!(normalize_panel_order(&[1, 1, 99]), [1, 0, 2]);
        assert_eq!(normalize_panel_order(&[2]), [2, 0, 1]);
        assert_eq!(normalize_panel_order(&[]), [0, 1, 2]);
    }

    #[test]
    fn hidden_panel_state_and_normalized_order_keep_all_geometry_consistent() {
        let layout = compute_panel_layout(1280, 800, false, true, 220, 280,
            &normalize_panel_order(&[2, 2, 99])).unwrap();
        assert_eq!((layout.list_left, layout.list_right), (0, 0));
        assert!(layout.info_right > layout.info_left);
        assert!(layout.map_right > layout.map_left);
        assert!(layout.info_right <= layout.map_left || layout.map_right <= layout.info_left);
    }

    #[test]
    fn rejects_invalid_viewport_sizes() {
        assert!(compute_panel_layout(0, 800, true, true, 220, 280, &[0, 1, 2]).is_none());
        assert!(compute_panel_layout(800, 20_000, true, true, 220, 280, &[0, 1, 2]).is_none());
    }
}
