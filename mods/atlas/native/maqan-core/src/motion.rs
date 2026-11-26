//! Deterministic animation curves shared by native MAQAN UI components.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum Easing {
    Linear = 0,
    SmoothStep = 1,
    EaseOutCubic = 2,
    EaseInOutCubic = 3,
}

/// Clamp and ease normalized animation progress. Invalid inputs settle safely at 1.
pub fn progress(value: f32, easing: Easing) -> f32 {
    let t = if value.is_finite() { value.clamp(0.0, 1.0) } else { 1.0 };
    match easing {
        Easing::Linear => t,
        Easing::SmoothStep => t * t * (3.0 - 2.0 * t),
        Easing::EaseOutCubic => 1.0 - (1.0 - t).powi(3),
        Easing::EaseInOutCubic => if t < 0.5 { 4.0 * t * t * t } else { 1.0 - (-2.0 * t + 2.0).powi(3) * 0.5 },
    }
}

/// Interpolate finite scalar values using a normalized easing curve.
pub fn lerp(from: f32, to: f32, value: f32, easing: Easing) -> f32 {
    if !from.is_finite() || !to.is_finite() { return 0.0; }
    from + (to - from) * progress(value, easing)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn easing_curves_have_stable_endpoints() {
        for curve in [Easing::Linear, Easing::SmoothStep, Easing::EaseOutCubic, Easing::EaseInOutCubic] {
            assert_eq!(progress(0.0, curve), 0.0);
            assert_eq!(progress(1.0, curve), 1.0);
        }
    }

    #[test]
    fn curves_clamp_invalid_and_out_of_range_progress() {
        assert_eq!(progress(-2.0, Easing::SmoothStep), 0.0);
        assert_eq!(progress(3.0, Easing::SmoothStep), 1.0);
        assert_eq!(progress(f32::NAN, Easing::EaseOutCubic), 1.0);
    }

    #[test]
    fn interpolation_respects_easing_and_rejects_non_finite_endpoints() {
        assert!((lerp(10.0, 20.0, 0.5, Easing::SmoothStep) - 15.0).abs() < 0.0001);
        assert_eq!(lerp(f32::NAN, 20.0, 0.5, Easing::Linear), 0.0);
    }
}
