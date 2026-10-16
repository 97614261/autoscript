//! Fixed, bounded numeric tools; no platform objects, expressions or file access.
use lua_runtime::{LuaInput, LuaScalar};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

const SAFE_INTEGER: i64 = 9_007_199_254_740_991;
const MAX_NUMBER: f64 = 1_000_000_000_000.0;
static SESSION_SEED: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub(crate) struct UtilityState {
    random: u64,
}
impl Default for UtilityState {
    fn default() -> Self {
        let time = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| {
            d.as_secs()
                .wrapping_mul(1_000_000_000)
                .wrapping_add(u64::from(d.subsec_nanos()))
        });
        Self {
            random: time ^ SESSION_SEED.fetch_add(0x9e37_79b9_7f4a_7c15, Ordering::Relaxed),
        }
    }
}
impl UtilityState {
    fn next(&mut self) -> u64 {
        self.random = self.random.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut value = self.random;
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^ (value >> 31)
    }
    fn below(&mut self, range: u64) -> Result<u64, &'static str> {
        let rejected = range.wrapping_neg() % range;
        for _ in 0..128 {
            let value = self.next();
            if value >= rejected {
                return Ok(value % range);
            }
        }
        Err("random sampling budget exceeded")
    }
    fn random_value(&mut self, opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
        if args.len() != 2 {
            return Err("random requires two arguments");
        }
        let first = integer(&args[0])?;
        let second = integer(&args[1])?;
        if opcode == 1010 {
            if first > second {
                return Err("minimum exceeds maximum");
            }
            let range = u64::try_from(i128::from(second) - i128::from(first) + 1)
                .map_err(|_| "invalid random range")?;
            let offset = self.below(range)?;
            let value = i128::from(first) + i128::from(offset);
            return Ok(LuaScalar::Integer(
                i64::try_from(value).map_err(|_| "random overflow")?,
            ));
        }
        if !(1..=1024).contains(&first) {
            return Err("random text length must be 1..1024");
        }
        let alphabet: &[u8] = match second {
            1 => b"abcdefghijklmnopqrstuvwxyz",
            2 => b"ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            3 => b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
            4 => b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
            5 => b"abcdefghijklmnopqrstuvwxyz0123456789",
            _ => return Err("random text mode must be 1..5"),
        };
        let length = usize::try_from(first).map_err(|_| "invalid random text length")?;
        let range = u64::try_from(alphabet.len()).map_err(|_| "invalid alphabet")?;
        let mut text = Vec::with_capacity(length);
        for _ in 0..length {
            let index = usize::try_from(self.below(range)?).map_err(|_| "random index overflow")?;
            text.push(alphabet[index]);
        }
        Ok(LuaScalar::Bytes(text))
    }
    pub(crate) fn execute(
        &mut self,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<LuaScalar, &'static str> {
        match opcode {
            1010 | 1011 => self.random_value(opcode, args),
            1019..=1021 => color_channel(opcode, args),
            2002 => {
                if !args.is_empty() {
                    return Err("wall time takes no arguments");
                }
                let time = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map_err(|_| "wall time unavailable")?;
                Ok(LuaScalar::Integer(
                    i64::try_from(time.as_millis()).map_err(|_| "wall time overflow")?,
                ))
            }
            _ => numeric(opcode, args),
        }
    }
}
fn integer(value: &LuaScalar) -> Result<i64, &'static str> {
    match value {
        LuaScalar::Integer(value) if (-SAFE_INTEGER..=SAFE_INTEGER).contains(value) => Ok(*value),
        _ => Err("expected exact bounded integer"),
    }
}
fn number(value: &LuaScalar) -> Result<f64, &'static str> {
    let value = match value {
        LuaScalar::Number(value) => *value,
        // Small integers convert exactly through From<i32>, without allocating
        // decimal text. Preserve the existing large-integer conversion/budget.
        LuaScalar::Integer(value) => match i32::try_from(*value) {
            Ok(small) => f64::from(small),
            Err(_) => value.to_string().parse().map_err(|_| "invalid number")?,
        },
        _ => return Err("expected number"),
    };
    if !value.is_finite() || value.abs() > MAX_NUMBER {
        return Err("number exceeds finite budget");
    }
    Ok(value)
}
fn color_channel(opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    let [value] = args else {
        return Err("color requires one argument");
    };
    let value = integer(value)?;
    if !(0..=0x00ff_ffff).contains(&value) {
        return Err("color requires 0xRRGGBB");
    }
    let shift = (1021 - opcode) * 8;
    Ok(LuaScalar::Integer((value >> shift) & 255))
}
fn numeric(opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    let arity = match opcode {
        1000 | 1015 => 4,
        1012 => 1,
        1013 | 1014 | 1017 | 1018 => 3,
        1016 => 2,
        _ => return Err("unknown numeric operation"),
    };
    if args.len() != arity {
        return Err("invalid numeric argument count");
    }
    // All numeric contracts have at most four arguments. Keep validation order
    // and conversion semantics, but avoid a heap allocation for every call.
    let mut values = [0.0; 4];
    for (slot, value) in values.iter_mut().zip(args) {
        *slot = number(value)?;
    }
    let a = values[0];
    let result = match opcode {
        1000 => (values[2] - a).hypot(values[3] - values[1]),
        1012 => {
            return Ok(LuaScalar::Integer(
                format!("{:.0}", a.round())
                    .parse()
                    .map_err(|_| "rounded integer overflow")?,
            ))
        }
        1013 | 1014 => {
            let (min, max) = (values[1], values[2]);
            if min > max {
                return Err("minimum exceeds maximum");
            }
            if opcode == 1014 {
                return Ok(LuaScalar::Integer(i64::from(a >= min && a <= max)));
            }
            a.clamp(min, max)
        }
        1015 => {
            let (dx, dy) = (values[2] - a, values[3] - values[1]);
            if dx == 0.0 && dy == 0.0 {
                return Err("identical points have no direction");
            }
            dy.atan2(dx).to_degrees()
        }
        1016 => {
            if !(-180.0..=180.0).contains(&a) {
                return Err("angle must be -180..180");
            }
            let angle = match integer(&args[1])? {
                1 => -a,
                2 => 180.0 - a,
                _ => return Err("flip mode must be 1 or 2"),
            };
            (angle + 180.0).rem_euclid(360.0) - 180.0
        }
        1017 | 1018 => {
            if !(-180.0..=180.0).contains(&values[1])
                || !(0.0..=1_000_000_000.0).contains(&values[2])
            {
                return Err("invalid joystick angle or radius");
            }
            let angle = values[1].to_radians();
            a + values[2]
                * if opcode == 1017 {
                    angle.cos()
                } else {
                    angle.sin()
                }
        }
        _ => return Err("unknown numeric operation"),
    };
    if !result.is_finite() {
        return Err("numeric result must be finite");
    }
    Ok(LuaScalar::Number(result))
}
pub(crate) fn resume(result: Result<LuaScalar, &'static str>) -> LuaInput {
    match result {
        Ok(value) => LuaInput::Values(vec![LuaScalar::Boolean(true), value]),
        Err(message) => LuaInput::Values(vec![
            LuaScalar::Boolean(false),
            LuaScalar::Nil,
            LuaScalar::Nil,
            LuaScalar::Bytes(b"UTILITY_INVALID".to_vec()),
            LuaScalar::Bytes(message.as_bytes().to_vec()),
        ]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn real_lua_calls_obey_capabilities_and_do_not_dispatch_random_to_host() {
        use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
        for allowed in [true, false] {
            let mut rt = crate::RuntimeExecutor::new(
                ManualClock::new(MonoTime::ZERO),
                SchedulerConfig::default(),
                lua_runtime::LuaRuntimeConfig::default(),
                crate::ExecutorConfig::default(),
                crate::VirtualHost::new(720, 1280),
            )
            .unwrap();
            rt.set_allowed_capabilities(&if allowed {
                vec!["core.math".into(), "core.task".into()]
            } else {
                vec!["core.task".into()]
            })
            .unwrap();
            let owner=rt.start_entry_chunk(b"return function() answer=Math.randInt(7,7); assert(answer==7); assert(#Math.randStr(12,4)==12); assert(Math.red(0xff0000)==255); assert(Math.round(-3.5)==-4); assert(Math.distance(0,0,3,4)==5); assert(System.getTimeMillis()>0) end","utility-lua").unwrap();
            for _ in 0..8 {
                rt.drive().unwrap();
            }
            assert_eq!(
                rt.task_state(owner),
                Some(if allowed {
                    TaskState::Completed
                } else {
                    TaskState::Failed
                })
            );
            assert!(rt.backend().requests().is_empty());
            if !allowed {
                assert_eq!(rt.task_failure(owner).unwrap().code, "LUA_RUNTIME_ERROR");
            }
        }
    }
    fn i(value: i64) -> LuaScalar {
        LuaScalar::Integer(value)
    }
    #[test]
    fn integer_numeric_fast_path_matches_decimal_reference_and_errors() {
        for value in [
            i64::MIN,
            -1_000_000_000_001,
            -1_000_000_000_000,
            i64::from(i32::MIN) - 1,
            i64::from(i32::MIN),
            -1,
            0,
            1,
            42,
            i64::from(i32::MAX),
            i64::from(i32::MAX) + 1,
            1_000_000_000_000,
            1_000_000_000_001,
            i64::MAX,
        ] {
            let old: f64 = value.to_string().parse().unwrap();
            let expected = if old.abs() > MAX_NUMBER {
                Err("number exceeds finite budget")
            } else {
                Ok(old)
            };
            assert_eq!(number(&i(value)), expected);
        }
        assert_eq!(
            number(&LuaScalar::Number(-0.0)).unwrap().to_bits(),
            (-0.0_f64).to_bits()
        );
        assert_eq!(
            number(&LuaScalar::Number(f64::NAN)),
            Err("number exceeds finite budget")
        );
        assert_eq!(number(&LuaScalar::Boolean(false)), Err("expected number"));
        let mut state = UtilityState::default();
        assert_eq!(
            state.execute(1013, &[i(42), i(0), i(100)]).unwrap(),
            LuaScalar::Number(42.0)
        );
        assert_eq!(
            state.execute(1013, &[i(42), i(100), i(0)]),
            Err("minimum exceeds maximum")
        );
    }
    #[test]
    fn numeric_geometry_color_and_strict_bounds() {
        let mut state = UtilityState { random: 1 };
        assert_eq!(
            state.execute(1000, &[i(0), i(0), i(3), i(4)]).unwrap(),
            LuaScalar::Number(5.0)
        );
        assert_eq!(
            state.execute(1012, &[LuaScalar::Number(-3.5)]).unwrap(),
            i(-4)
        );
        assert_eq!(state.execute(1014, &[i(5), i(5), i(6)]).unwrap(), i(1));
        assert_eq!(
            state.execute(1015, &[i(0), i(0), i(0), i(1)]).unwrap(),
            LuaScalar::Number(90.0)
        );
        assert_eq!(
            state.execute(1016, &[i(45), i(2)]).unwrap(),
            LuaScalar::Number(135.0)
        );
        assert_eq!(state.execute(1019, &[i(0xab_cdef)]).unwrap(), i(171));
        assert_eq!(state.execute(1020, &[i(0xab_cdef)]).unwrap(), i(205));
        assert_eq!(state.execute(1021, &[i(0xab_cdef)]).unwrap(), i(239));
        for (op, args) in [
            (1013, vec![i(5), i(6), i(0)]),
            (1015, vec![i(0); 4]),
            (1019, vec![i(-1)]),
            (1012, vec![LuaScalar::Number(f64::NAN)]),
            (1012, vec![LuaScalar::Number(f64::INFINITY)]),
            (1016, vec![i(181), i(1)]),
            (1017, vec![i(0), i(0), i(-1)]),
        ] {
            assert!(state.execute(op, &args).is_err());
        }
    }
    #[test]
    fn random_is_bounded_inclusive_and_has_exact_mode() {
        let mut state = UtilityState { random: 0 };
        let mut seen = [false; 3];
        for _ in 0..1000 {
            let LuaScalar::Integer(n) = state.execute(1010, &[i(-1), i(1)]).unwrap() else {
                panic!("integer");
            };
            seen[usize::try_from(n + 1).unwrap()] = true;
        }
        assert!(seen.into_iter().all(|v| v));
        assert_eq!(
            state
                .execute(1010, &[i(SAFE_INTEGER), i(SAFE_INTEGER)])
                .unwrap(),
            i(SAFE_INTEGER)
        );
        assert!(state.execute(1010, &[i(2), i(1)]).is_err());
        assert!(state
            .execute(1010, &[LuaScalar::Number(1.0), i(2)])
            .is_err());
        for mode in 1..=5 {
            let LuaScalar::Bytes(text) = state.execute(1011, &[i(1024), i(mode)]).unwrap() else {
                panic!("text");
            };
            assert_eq!(text.len(), 1024);
            assert!(text.iter().all(|c| match mode {
                1 => c.is_ascii_lowercase(),
                2 => c.is_ascii_uppercase(),
                3 => c.is_ascii_alphabetic(),
                4 => c.is_ascii_alphanumeric(),
                _ => c.is_ascii_lowercase() || c.is_ascii_digit(),
            }));
        }
        assert!(state.execute(1011, &[i(1025), i(4)]).is_err());
        assert!(state.execute(1011, &[i(1), i(6)]).is_err());
        assert!(matches!(state.execute(2002,&[]),Ok(LuaScalar::Integer(t)) if t>0));
    }
}
