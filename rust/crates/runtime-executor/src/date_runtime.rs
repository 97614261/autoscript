use lua_runtime::LuaScalar;
const DAY: i64 = 86_400_000;
const LAST: i64 = 253_402_300_799_999;
fn integer(args: &[LuaScalar], index: usize) -> Result<i64, &'static str> {
    match args.get(index) {
        Some(LuaScalar::Integer(v)) => Ok(*v),
        _ => Err("date requires integer timestamp/offset"),
    }
}
fn stamp(args: &[LuaScalar], index: usize) -> Result<i64, &'static str> {
    let v = integer(args, index)?;
    if !(0..=LAST).contains(&v) {
        return Err("date timestamp outside 1970..9999");
    }
    Ok(v)
}
fn leap(year: i64) -> bool {
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
pub(crate) fn execute(opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    if args.len() != if opcode == 14300 { 3 } else { 2 } {
        return Err("invalid date argument count");
    }
    let first = stamp(args, 0)?;
    match opcode {
        14301 => {
            let days = integer(args, 1)?;
            let result = days
                .checked_mul(DAY)
                .and_then(|n| first.checked_add(n))
                .filter(|v| (0..=LAST).contains(v))
                .ok_or("date arithmetic out of range")?;
            Ok(LuaScalar::Integer(result))
        }
        14302 => {
            let difference = stamp(args, 1)? - first;
            let value = difference
                .to_string()
                .parse::<f64>()
                .map_err(|_| "date difference unavailable")?;
            Ok(LuaScalar::Number(value / 86_400_000.0))
        }
        14300 => format(first, args),
        _ => Err("unknown date operation"),
    }
}
fn format(first: i64, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    let offset = integer(args, 1)?;
    if !(-840..=840).contains(&offset) {
        return Err("date offset must be -840..840 minutes");
    }
    let local = first + offset * 60_000;
    let mut days = local.div_euclid(DAY);
    let mut year = 1970;
    if days < 0 {
        year = 1969;
        days += 365;
    }
    year += (days / 146_097) * 400;
    days %= 146_097;
    loop {
        let length = if leap(year) { 366 } else { 365 };
        if days < length {
            break;
        }
        days -= length;
        year += 1;
    }
    if year > 9999 {
        return Err("formatted date exceeds year 9999");
    }
    let lengths = [
        31,
        if leap(year) { 29 } else { 28 },
        31,
        30,
        31,
        30,
        31,
        31,
        30,
        31,
        30,
        31,
    ];
    let mut month = 0usize;
    while days >= lengths[month] {
        days -= lengths[month];
        month += 1;
    }
    let date = format!("{year:04}-{:02}-{:02}", month + 1, days + 1);
    let seconds = local.rem_euclid(DAY) / 1000;
    let time = format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60
    );
    let Some(LuaScalar::Bytes(pattern)) = args.get(2) else {
        return Err("date format must be string");
    };
    let value = match pattern.as_slice() {
        b"date" => date,
        b"time" => time,
        b"datetime" => format!("{date} {time}"),
        _ => return Err("format must be date/time/datetime"),
    };
    Ok(LuaScalar::Bytes(value.into_bytes()))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn epoch_offsets_leap_days_and_overflow() {
        let i = LuaScalar::Integer;
        let b = |s: &str| LuaScalar::Bytes(s.as_bytes().to_vec());
        assert_eq!(
            execute(14300, &[i(0), i(480), b("datetime")]).unwrap(),
            b("1970-01-01 08:00:00")
        );
        assert_eq!(
            execute(14300, &[i(0), i(-60), b("datetime")]).unwrap(),
            b("1969-12-31 23:00:00")
        );
        assert_eq!(
            execute(14300, &[i(951_782_400_000), i(0), b("date")]).unwrap(),
            b("2000-02-29")
        );
        assert_eq!(
            execute(14302, &[i(DAY), i(0)]).unwrap(),
            LuaScalar::Number(-1.0)
        );
        assert!(execute(14301, &[i(0), i(-1)]).is_err());
        assert!(execute(14300, &[i(LAST), i(840), b("date")]).is_err());
    }
}
