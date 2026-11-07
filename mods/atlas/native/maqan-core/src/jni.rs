//! Minimal dependency-free JNI exports for the MAQAN in-game UI bridge.
//! All commands are composed in Rust; Java only submits them to Minecraft's
//! existing GuiGraphics/LWJGL context.

use std::ffi::c_void;
use std::mem;
use crate::ui::{ButtonState, Color, Rect, TextRole, UiFrame};

type JNIEnvPtr = *mut *const *const c_void;
type JObject = *mut c_void;
type JByteArray = JObject;

fn compose_chrome(width: u32, height: u32, _time: f32, flags: i32) -> Vec<u8> {
    if width == 0 || height == 0 { return Vec::new(); }
    let w = width as f32;
    let h = height as f32;
    let mut frame = UiFrame::new(width, height, 1.0);
    frame.fill(Rect::new(0.0, 0.0, w, h), Color::BACKGROUND);

    // Minimal workspace chrome: no starfield, nebula blooms, animated sweeps
    // or corner brackets. The 2D/3D celestial scene owns the visual focus.
    let header_h = 38.0_f32.min(h);
    frame.fill(Rect::new(0.0, 0.0, w, header_h), Color(0xB90D1520));
    frame.line(0.0, header_h - 1.0, w, header_h - 1.0, Color(0x55475D70), 1.0);
    frame.line(14.0, header_h - 1.0, if flags & 1 != 0 { 46.0 } else { 58.0 },
        header_h - 1.0, Color::CYAN, 1.0);

    // One faint viewport guide is enough to distinguish the workspace canvas.
    if w > 240.0 && h > 180.0 {
        let inset = if flags & 1 != 0 { 8.0 } else { 14.0 };
        frame.line(inset, header_h + 10.0, inset, h - inset, Color(0x123E91B0), 1.0);
        frame.line(w - inset, header_h + 10.0, w - inset, h - inset, Color(0x123E91B0), 1.0);
    }
    frame.encode().unwrap_or_default()
}

unsafe fn read_java_bytes(env: JNIEnvPtr, array: JObject, max_len: i32) -> Option<Vec<u8>> {
    if env.is_null() || array.is_null() { return None; }
    let table = *env;
    if table.is_null() { return None; }
    let get_array_length: extern "system" fn(JNIEnvPtr, JObject) -> i32 =
        mem::transmute(*table.add(171));
    let get_byte_array_region: extern "system" fn(JNIEnvPtr, JObject, i32, i32, *mut i8) =
        mem::transmute(*table.add(200));
    let exception_check: extern "system" fn(JNIEnvPtr) -> u8 =
        mem::transmute(*table.add(228));
    let length = get_array_length(env, array);
    if length < 0 || length > max_len { return None; }
    let mut bytes = vec![0_i8; length as usize];
    if length > 0 { get_byte_array_region(env, array, 0, length, bytes.as_mut_ptr()); }
    if exception_check(env) != 0 { return None; }
    Some(std::slice::from_raw_parts(bytes.as_ptr().cast::<u8>(), bytes.len()).to_vec())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeTitle(
    env: JNIEnvPtr, _class: JObject, width: i32, title_array: JObject, subtitle_array: JObject,
    subtitle_width: i32, align_right: u8,
) -> JByteArray {
    if width <= 0 || width > 16_384 { return std::ptr::null_mut(); }
    let Some(title_bytes) = read_java_bytes(env, title_array, 4096) else { return std::ptr::null_mut(); };
    let Some(subtitle_bytes) = read_java_bytes(env, subtitle_array, 8192) else { return std::ptr::null_mut(); };
    let title = String::from_utf8_lossy(&title_bytes).into_owned();
    let subtitle = String::from_utf8_lossy(&subtitle_bytes).into_owned();
    let mut frame = UiFrame::new(width as u32, 48, 1.0);
    frame.text(18.0, 13.0, title, TextRole::Heading);
    if !subtitle.trim().is_empty() {
        let subtitle_x = if align_right != 0 {
            (width - 18 - subtitle_width.clamp(0, width)).max(18) as f32
        } else { 18.0 };
        frame.text(subtitle_x, 27.0, subtitle, TextRole::Muted);
    }
    frame.line(12.0, 42.0, (width - 12).max(12) as f32, 42.0, Color(0x665D7895), 1.0);
    frame.line(12.0, 42.0, (width.min(52)) as f32, 42.0, Color::CYAN, 1.0);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeSectionHeader(
    env: JNIEnvPtr, _class: JObject, x: i32, y: i32, width: i32, height: i32,
    phase: f32, label_width: i32, label_array: JObject,
) -> JByteArray {
    if x < 0 || y < 0 || width <= 0 || height <= 0 || width > 16_384 || height > 256
        || x.saturating_add(width) > 16_384 || y.saturating_add(height) > 16_384
        || label_width < 0 || label_width > width {
        return std::ptr::null_mut();
    }
    let Some(bytes) = read_java_bytes(env, label_array, 4096) else {
        return std::ptr::null_mut();
    };
    let label = String::from_utf8_lossy(&bytes).into_owned();
    let mut frame = UiFrame::new(x.saturating_add(width) as u32, y.saturating_add(height) as u32, 1.0);
    frame.section_header(Rect::new(x as f32, y as f32, width as f32, height as f32),
        label, label_width as f32, phase);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeAbiVersion(
    _env: JNIEnvPtr, _class: JObject,
) -> i32 { 1 }

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeChrome(
    env: JNIEnvPtr, _class: JObject, width: i32, height: i32, time: f32, flags: i32,
) -> JByteArray {
    if env.is_null() || width <= 0 || height <= 0 || width > 16_384 || height > 16_384 {
        return std::ptr::null_mut();
    }
    let packet = compose_chrome(width as u32, height as u32, time, flags);
    if packet.is_empty() || packet.len() > 16 * 1024 * 1024 { return std::ptr::null_mut(); }

    // JNI function table slots are defined by the JNI specification:
    // NewByteArray = 176, SetByteArrayRegion = 208.
    let table = *env;
    if table.is_null() { return std::ptr::null_mut(); }
    let new_byte_array: extern "system" fn(JNIEnvPtr, i32) -> JByteArray =
        mem::transmute(*table.add(176));
    let set_byte_array_region: extern "system" fn(JNIEnvPtr, JByteArray, i32, i32, *const i8) =
        mem::transmute(*table.add(208));
    let array = new_byte_array(env, packet.len() as i32);
    if array.is_null() { return std::ptr::null_mut(); }
    set_byte_array_region(env, array, 0, packet.len() as i32, packet.as_ptr().cast());
    let exception_check: extern "system" fn(JNIEnvPtr) -> u8 =
        mem::transmute(*table.add(228));
    if exception_check(env) != 0 { return std::ptr::null_mut(); }
    array
}



#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposePanel(
    _env: JNIEnvPtr, _class: JObject, left: i32, top: i32, right: i32, bottom: i32,
    time: f32, accent: i32,
) -> JByteArray {
    if left < 0 || top < 0 || right <= left || bottom <= top || right > 16_384 || bottom > 16_384 {
        return std::ptr::null_mut();
    }
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.panel_glow(
        Rect::new(left as f32, top as f32, (right-left) as f32, (bottom-top) as f32),
        Color(accent as u32),
        time,
    );
    // JNI array allocation is performed by the shared packet helper below.
    make_byte_array(_env, &frame.encode().unwrap_or_default())
}

unsafe fn make_byte_array(env: JNIEnvPtr, packet: &[u8]) -> JByteArray {
    if env.is_null() || packet.is_empty() || packet.len() > 16 * 1024 * 1024 {
        return std::ptr::null_mut();
    }
    let table = *env;
    if table.is_null() { return std::ptr::null_mut(); }
    let new_byte_array: extern "system" fn(JNIEnvPtr, i32) -> JByteArray =
        mem::transmute(*table.add(176));
    let set_byte_array_region: extern "system" fn(JNIEnvPtr, JByteArray, i32, i32, *const i8) =
        mem::transmute(*table.add(208));
    let array = new_byte_array(env, packet.len() as i32);
    if array.is_null() { return std::ptr::null_mut(); }
    set_byte_array_region(env, array, 0, packet.len() as i32, packet.as_ptr().cast());
    let exception_check: extern "system" fn(JNIEnvPtr) -> u8 =
        mem::transmute(*table.add(228));
    if exception_check(env) != 0 { return std::ptr::null_mut(); }
    array
}


#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComputePanelLayout(
    _env: JNIEnvPtr, _class: JObject, width: i32, height: i32,
    show_list: u8, show_info: u8, list_width: i32, info_width: i32, order_0: i32, order_1: i32, order_2: i32,
) -> JByteArray {
    if width < 1 || height < 1 || width > 16_384 || height > 16_384 {
        return std::ptr::null_mut();
    }
    let order = [order_0, order_1, order_2].map(|v| if (0..=2).contains(&v) { v as u8 } else { 255 });
    let Some(layout) = crate::layout::compute_panel_layout(width, height, show_list != 0, show_info != 0, list_width, info_width, &order) else {
        return std::ptr::null_mut();
    };
    let values = [layout.map_top, layout.map_bottom, layout.list_left, layout.list_right,
        layout.info_left, layout.info_right, layout.map_left, layout.map_right];
    let mut packet = Vec::with_capacity(40);
    packet.extend_from_slice(b"MLAY");
    packet.extend_from_slice(&1_u16.to_le_bytes());
    packet.extend_from_slice(&0_u16.to_le_bytes());
    for value in values { packet.extend_from_slice(&value.to_le_bytes()); }
    make_byte_array(_env, &packet)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeTransitionOverlay(
    env: JNIEnvPtr, _class: JObject, width: i32, height: i32, progress: f32, direction: i32,
) -> JByteArray {
    if env.is_null() || width < 80 || height < 60 || width > 16_384 || height > 16_384 || direction < 0 || direction > 1 {
        return std::ptr::null_mut();
    }
    let mut frame = UiFrame::new(width as u32, height as u32, 1.0);
    frame.transition_overlay(Rect::new(0.0, 0.0, width as f32, height as f32), progress, direction as u8);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeContextMenu(
    env: JNIEnvPtr, _class: JObject, left: i32, top: i32, right: i32, bottom: i32,
    phase: f32, hovered_row: i32, opacity: f32,
) -> JByteArray {
    if env.is_null() || left < 0 || top < 0 || right <= left || bottom <= top
        || right > 16_384 || bottom > 16_384 || bottom - top > 512
        || hovered_row < -1 || hovered_row > 3 {
        return std::ptr::null_mut();
    }
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.context_menu(Rect::new(left as f32, top as f32, (right-left) as f32, (bottom-top) as f32),
        phase, hovered_row, opacity);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeBreadcrumbChip(
    env: JNIEnvPtr, _class: JObject, left: i32, top: i32, right: i32, bottom: i32,
    phase: f32, state: i32,
) -> JByteArray {
    if env.is_null() || left < 0 || top < 0 || right <= left || bottom <= top
        || right > 16_384 || bottom > 16_384 || bottom - top > 128 || state < 0 || state > 2 {
        return std::ptr::null_mut();
    }
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.breadcrumb_chip(Rect::new(left as f32, top as f32, (right-left) as f32, (bottom-top) as f32), phase, state as u8);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeNavigationTab(
    env: JNIEnvPtr, _class: JObject, left: i32, top: i32, right: i32, bottom: i32,
    phase: f32, state: i32,
) -> JByteArray {
    if left < 0 || top < 0 || right <= left || bottom <= top || right > 16_384 || bottom > 16_384
        || bottom - top > 256 || state < 0 || state > 2 { return std::ptr::null_mut(); }
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.navigation_tab(Rect::new(left as f32, top as f32, (right-left) as f32, (bottom-top) as f32), phase, state as u8);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeCatalogueCard(
    env: JNIEnvPtr, _class: JObject, left: i32, top: i32, right: i32, bottom: i32,
    phase: f32, state: i32,
) -> JByteArray {
    if left < 0 || top < 0 || right <= left || bottom <= top || right > 16_384 || bottom > 16_384
        || bottom - top > 256 || state < 0 || state > 2 {
        return std::ptr::null_mut();
    }
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.catalogue_card(Rect::new(left as f32, top as f32, (right-left) as f32, (bottom-top) as f32),
        phase, state as u8);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeDossierCard(
    env: JNIEnvPtr, _class: JObject, left: i32, top: i32, right: i32, bottom: i32,
    phase: f32, selected: u8,
) -> JByteArray {
    if left < 0 || top < 0 || right <= left || bottom <= top || right > 16_384 || bottom > 16_384
        || bottom - top > 512 {
        return std::ptr::null_mut();
    }
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.dossier_card(Rect::new(left as f32, top as f32, (right-left) as f32, (bottom-top) as f32),
        phase, selected != 0);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeComposeButton(
    env: JNIEnvPtr, _class: JObject, x: i32, y: i32, width: i32, height: i32,
    time: f32, state: i32, label_width: i32, label_array: JObject,
) -> JByteArray {
    if env.is_null() || label_array.is_null() || x < 0 || y < 0 || width <= 0 || height <= 0
        || x.saturating_add(width) > 16_384 || y.saturating_add(height) > 16_384 {
        return std::ptr::null_mut();
    }
    let Some(bytes) = read_java_bytes(env, label_array, 4096) else {
        return std::ptr::null_mut();
    };
    let label = String::from_utf8_lossy(&bytes).into_owned();
    let state = match state {
        1 => ButtonState::Hovered,
        2 => ButtonState::Disabled,
        _ => ButtonState::Normal,
    };
    let right = x + width;
    let bottom = y + height;
    let mut frame = UiFrame::new(right as u32, bottom as u32, 1.0);
    frame.button_glow(Rect::new(x as f32, y as f32, width as f32, height as f32), label, state, time, label_width as f32);
    make_byte_array(env, &frame.encode().unwrap_or_default())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_maqan_galacticnavigator_navigation_NavigatorNativeBridge_nativeRouteExploredChunks(
    env: JNIEnvPtr, _class: JObject, dimension_low: i32, dimension_high: i32,
    explored: JObject, start_x: i32, start_z: i32, goal_x: i32, goal_z: i32,
    max_expansions: i32,
) -> JObject {
    if env.is_null() || explored.is_null() { return std::ptr::null_mut(); }
    let table = *env;
    if table.is_null() { return std::ptr::null_mut(); }
    // JNI slots: GetArrayLength=171, GetIntArrayRegion=203,
    // NewIntArray=179, SetIntArrayRegion=211.
    let get_array_length: extern "system" fn(JNIEnvPtr, JObject) -> i32 =
        mem::transmute(*table.add(171));
    let get_int_array_region: extern "system" fn(JNIEnvPtr, JObject, i32, i32, *mut i32) =
        mem::transmute(*table.add(203));
    let new_int_array: extern "system" fn(JNIEnvPtr, i32) -> JObject =
        mem::transmute(*table.add(179));
    let set_int_array_region: extern "system" fn(JNIEnvPtr, JObject, i32, i32, *const i32) =
        mem::transmute(*table.add(211));
    let length = get_array_length(env, explored);
    if length < 0 || length % 3 != 0 || length > 6_000_000 { return std::ptr::null_mut(); }
    let mut values = vec![0_i32; length as usize];
    if length > 0 { get_int_array_region(env, explored, 0, length, values.as_mut_ptr()); }

    let dimension = ((dimension_high as u32 as u64) << 32) | dimension_low as u32 as u64;
    let mut index = crate::ExplorationIndex::default();
    for chunk in values.chunks_exact(3) {
        index.record(
            crate::TileKey { dimension_hash: dimension, x: chunk[0], z: chunk[1] },
            crate::SurfaceTile { biome_color: 0, height: chunk[2], flags: 0 },
        );
    }
    let route = index.route(dimension, (start_x, start_z), (goal_x, goal_z),
        max_expansions.clamp(1, 2_000_000) as usize);
    let mut packed = Vec::with_capacity(route.points.len().saturating_mul(2));
    for point in route.points { packed.push(point.x); packed.push(point.z); }
    let result = new_int_array(env, packed.len() as i32);
    if result.is_null() { return std::ptr::null_mut(); }
    if !packed.is_empty() {
        set_int_array_region(env, result, 0, packed.len() as i32, packed.as_ptr());
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chrome_packet_is_versioned_and_stable() {
        let a = compose_chrome(1280, 720, 0.0, 0);
        let b = compose_chrome(1280, 720, 1.0, 0);
        assert_eq!(&a[0..4], b"MQUI");
        assert_eq!(&b[0..4], b"MQUI");
        assert_eq!(a, b, "minimal workspace chrome must not animate decorative background noise");
    }

    #[test]
    fn rejects_unbounded_dimensions_at_jni_boundary() {
        assert!(compose_chrome(1, 1, 0.0, 0).len() > 20);
        assert!(compose_chrome(0, 720, 0.0, 0).is_empty());
    }
}
