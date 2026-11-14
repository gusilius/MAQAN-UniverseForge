//! JNI boundary for the pure Rust decision and generation engines.
use std::{ffi::c_void, mem};
use crate::{decide, WorldSnapshot};
use crate::worldgen::{generate_world, WorldGenRequest};
use crate::emergence::{advance, EmergenceRequest};

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
fn has_supported_schema(bytes: &[u8]) -> bool {
    serde_json::from_slice::<serde_json::Value>(bytes)
        .ok()
        .and_then(|value| value.get("schema_version").and_then(|v| v.as_u64()))
        == Some(1)
}

fn error_json(status: &str, error: &str) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({"status":status,"error":error}))
        .unwrap_or_else(|_| br#"{"status":"serialization_error"}"#.to_vec())
}

/// Version of the JNI/data contract implemented by this exact native library.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_worldforge_worldsim_mc_NativeWorldSimulation_nativeAbiVersion(
    _env: JNIEnvPtr, _class: JObject,
) -> i32 {
    1
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_worldforge_worldsim_mc_NativeWorldSimulation_nativeDecide(
    env: JNIEnvPtr, _class: JObject, input: JObject,
) -> JObject {
    let response = match read_bytes(env, input, 16 * 1024 * 1024) {
        None => error_json("invalid_input", "JNI input unavailable or too large"),
        Some(bytes) if !has_supported_schema(&bytes) => error_json("unsupported_schema", "expected snapshot schema_version=1"),
        Some(bytes) => match serde_json::from_slice::<WorldSnapshot>(&bytes) {
            Ok(snapshot) => match decide(&snapshot) {
                Ok(frame) => serde_json::to_vec(&frame).unwrap_or_else(|_| error_json("serialization_error", "decision frame")),
                Err(error) => error_json("rejected", &format!("{error:?}")),
            },
            Err(error) => error_json("invalid_snapshot", &error.to_string()),
        },
    };
    make_bytes(env, &response)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_worldforge_worldsim_mc_NativeWorldSimulation_nativeGenerate(
    env: JNIEnvPtr, _class: JObject, input: JObject,
) -> JObject {
    let response = match read_bytes(env, input, 1024 * 1024) {
        None => error_json("invalid_input", "JNI input unavailable or too large"),
        Some(bytes) => match serde_json::from_slice::<WorldGenRequest>(&bytes) {
            Ok(request) => match generate_world(&request) {
                Ok(world) => serde_json::to_vec(&world).unwrap_or_else(|_| error_json("serialization_error", "generated world")),
                Err(error) => error_json("generation_rejected", &format!("{error:?}")),
            },
            Err(error) => error_json("invalid_request", &error.to_string()),
        },
    };
    make_bytes(env, &response)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_worldforge_worldsim_mc_NativeWorldSimulation_nativeAdvanceEmergence(
    env: JNIEnvPtr, _class: JObject, input: JObject,
) -> JObject {
    let response = match read_bytes(env, input, 16 * 1024 * 1024) {
        None => error_json("invalid_input", "emergence input unavailable or too large"),
        Some(bytes) if !has_supported_schema(&bytes) => error_json("unsupported_schema", "expected emergence request schema_version=1"),
        Some(bytes) => match serde_json::from_slice::<EmergenceRequest>(&bytes) {
            Ok(request) => match advance(request) {
                Ok(frame) => serde_json::to_vec(&frame).unwrap_or_else(|_| error_json("serialization_error", "emergence frame")),
                Err(error) => error_json("emergence_rejected", &error),
            },
            Err(error) => error_json("invalid_request", &error.to_string()),
        },
    };
    make_bytes(env, &response)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_worldforge_worldsim_mc_NativeWorldSimulation_nativeAdvancePopulation(
    env: JNIEnvPtr, _class: JObject, input: JObject,
) -> JObject {
    let response = match read_bytes(env, input, 16 * 1024 * 1024) {
        None => error_json("invalid_input", "population input unavailable or too large"),
        Some(bytes) if !has_supported_schema(&bytes) => error_json("unsupported_schema", "expected population schema_version=1"),
        Some(bytes) => match serde_json::from_slice::<crate::population::Request>(&bytes) {
            Ok(request) => match crate::population::advance(request) {
                Ok(frame) => serde_json::to_vec(&frame).unwrap_or_else(|_| error_json("serialization_error", "population frame")),
                Err(error) => error_json("population_rejected", &error),
            },
            Err(error) => error_json("invalid_population_request", &error.to_string()),
        },
    };
    make_bytes(env, &response)
}
