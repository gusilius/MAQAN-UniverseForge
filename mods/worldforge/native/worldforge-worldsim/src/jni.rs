//! JNI boundary for the pure Rust decision engine. Java sends UTF-8 JSON and
//! receives UTF-8 JSON. All authoritative state remains on the Java side.
use std::{ffi::c_void, mem};
use crate::{decide, WorldSnapshot};

type JNIEnvPtr = *mut *const *const c_void;
type JObject = *mut c_void;

unsafe fn read_bytes(env: JNIEnvPtr, array: JObject, max_len: i32) -> Option<Vec<u8>> {
    if env.is_null() || array.is_null() { return None; }
    let table = *env;
    if table.is_null() { return None; }
    let get_len: extern "system" fn(JNIEnvPtr, JObject) -> i32 = mem::transmute(*table.add(171));
    let get_region: extern "system" fn(JNIEnvPtr, JObject, i32, i32, *mut i8) = mem::transmute(*table.add(200));
    let exception: extern "system" fn(JNIEnvPtr) -> u8 = mem::transmute(*table.add(228));
    let len = get_len(env, array);
    if len < 0 || len > max_len { return None; }
    let mut bytes = vec![0_i8; len as usize];
    if len > 0 { get_region(env, array, 0, len, bytes.as_mut_ptr()); }
    if exception(env) != 0 { return None; }
    Some(std::slice::from_raw_parts(bytes.as_ptr().cast::<u8>(), bytes.len()).to_vec())
}

unsafe fn make_bytes(env: JNIEnvPtr, bytes: &[u8]) -> JObject {
    if env.is_null() || bytes.is_empty() || bytes.len() > 16 * 1024 * 1024 { return std::ptr::null_mut(); }
    let table = *env;
    if table.is_null() { return std::ptr::null_mut(); }
    let new_array: extern "system" fn(JNIEnvPtr, i32) -> JObject = mem::transmute(*table.add(176));
    let set_region: extern "system" fn(JNIEnvPtr, JObject, i32, i32, *const i8) = mem::transmute(*table.add(208));
    let exception: extern "system" fn(JNIEnvPtr) -> u8 = mem::transmute(*table.add(228));
    let array = new_array(env, bytes.len() as i32);
    if array.is_null() { return std::ptr::null_mut(); }
    set_region(env, array, 0, bytes.len() as i32, bytes.as_ptr().cast());
    if exception(env) != 0 { return std::ptr::null_mut(); }
    array
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_worldforge_worldsim_mc_NativeWorldSimulation_nativeDecide(
    env: JNIEnvPtr, _class: JObject, input: JObject,
) -> JObject {
    let response = match read_bytes(env, input, 16 * 1024 * 1024) {
        None => br#"{"status":"invalid_input","diagnostics":["JNI input unavailable or too large"]}"#.to_vec(),
        Some(bytes) => match serde_json::from_slice::<WorldSnapshot>(&bytes) {
            Ok(snapshot) => match decide(&snapshot) {
                Ok(frame) => serde_json::to_vec(&frame).unwrap_or_else(|_| br#"{"status":"serialization_error"}"#.to_vec()),
                Err(error) => serde_json::to_vec(&serde_json::json!({"status":"rejected","error":format!("{error:?}")}))
                    .unwrap_or_else(|_| br#"{"status":"serialization_error"}"#.to_vec()),
            },
            Err(error) => serde_json::to_vec(&serde_json::json!({"status":"invalid_snapshot","error":error.to_string()}))
                .unwrap_or_else(|_| br#"{"status":"serialization_error"}"#.to_vec()),
        },
    };
    make_bytes(env, &response)
}
