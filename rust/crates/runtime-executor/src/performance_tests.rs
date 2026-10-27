//! Opt-in fixed-input CPU baselines, not Android/Root latency claims.
use lua_runtime::LuaScalar;
use std::hint::black_box;
use std::time::Instant;

fn bytes(value: &str) -> LuaScalar {
    LuaScalar::Bytes(value.as_bytes().to_vec())
}

fn assert_output(
    name: &str,
    opcode: u32,
    args: &[LuaScalar],
    expected: &LuaScalar,
    utility: &mut super::utility_runtime::UtilityState,
) {
    let actual = match opcode {
        14200..=14224 => super::text_runtime::execute(opcode, args),
        14000..=14006 => super::data_runtime::execute(opcode, args),
        _ => utility.execute(opcode, args),
    }
    .unwrap();
    assert_eq!(&actual, expected, "baseline input correctness: {name}");
}

#[test]
#[ignore = "explicit fixed-input CPU performance matrix"]
fn benchmark_common_function_matrix() {
    let ascii = "abcdefgh01234567".repeat(4096);
    let unicode = "智构😀Ab09".repeat(2048);
    let json = serde_json::to_string(&(0..512).collect::<Vec<u32>>()).unwrap();
    let cases = [
        (
            "text-sub-ascii-64k-head",
            14209,
            vec![
                bytes(&ascii),
                LuaScalar::Integer(2),
                LuaScalar::Integer(4096),
            ],
            100,
        ),
        (
            "text-sub-unicode-head",
            14209,
            vec![
                bytes(&unicode),
                LuaScalar::Integer(2),
                LuaScalar::Integer(2048),
            ],
            100,
        ),
        (
            "text-sub-ascii-64k-tail",
            14209,
            vec![
                bytes(&ascii),
                LuaScalar::Integer(60_000),
                LuaScalar::Integer(1024),
            ],
            100,
        ),
        (
            "regex-digits-4k",
            14222,
            vec![bytes(&"A123 B456 ".repeat(400)), bytes("[0-9]+")],
            20,
        ),
        (
            "data-get-512-items",
            14001,
            vec![bytes(&json), bytes("512")],
            20,
        ),
        (
            "math-clamp-integers",
            1013,
            vec![
                LuaScalar::Integer(42),
                LuaScalar::Integer(0),
                LuaScalar::Integer(100),
            ],
            1000,
        ),
    ];
    let mut utility = super::utility_runtime::UtilityState::default();
    let expected = [
        bytes(&ascii.chars().skip(1).take(4096).collect::<String>()),
        bytes(&unicode.chars().skip(1).take(2048).collect::<String>()),
        bytes(&ascii.chars().skip(59_999).take(1024).collect::<String>()),
        LuaScalar::Integer(1),
        bytes("511"),
        LuaScalar::Number(42.0),
    ];
    for ((name, opcode, args, _), expected) in cases.iter().zip(expected) {
        assert_output(name, *opcode, args, &expected, &mut utility);
    }
    let mut samples = vec![Vec::new(); cases.len()];
    for round in 0..110 {
        for offset in 0..cases.len() {
            let index = (round + offset) % cases.len();
            let (_, opcode, args, batch) = &cases[index];
            let began = Instant::now();
            for _ in 0..*batch {
                let value = match *opcode {
                    14200..=14224 => super::text_runtime::execute(*opcode, black_box(args)),
                    14000..=14006 => super::data_runtime::execute(*opcode, black_box(args)),
                    _ => utility.execute(*opcode, black_box(args)),
                }
                .unwrap();
                black_box(value);
            }
            if round >= 10 {
                samples[index]
                    .push(began.elapsed().as_secs_f64() * 1_000_000.0 / f64::from(*batch));
            }
        }
    }
    for ((name, opcode, args, batch), mut sample) in cases.into_iter().zip(samples) {
        sample.sort_by(f64::total_cmp);
        println!(
            "PERF_CPU {}",
            serde_json::json!({"case":name,"opcode":opcode,"arch":std::env::consts::ARCH,
            "inputBytes":args.iter().map(|v| if let LuaScalar::Bytes(b)=v {b.len()} else {0}).sum::<usize>(),
            "samples":100,"warmupBatches":10,"callsPerBatch":batch,"unit":"amortized-us-per-call",
            "p50":sample[49],"p95":sample[94],"p99":sample[98],"scope":"direct fixed Rust operation; no Lua/JNI/Android/Root"})
        );
    }
}

#[test]
#[ignore = "explicit alternating compiled-pattern CPU comparison"]
fn benchmark_regex_reference_and_cache() {
    use runtime_scheduler::{TaskGeneration, TaskId};
    let task = runtime_scheduler::TaskToken {
        id: TaskId(1),
        generation: TaskGeneration(1),
    };
    let mut cache = super::regex_cache::RegexCache::default();
    let mut samples = [Vec::new(), Vec::new()];
    let args = [bytes(&"智构A123 B456 ".repeat(200)), bytes("[0-9]+")];
    for round in 0..110 {
        for mode in [round % 2, (round + 1) % 2] {
            let began = Instant::now();
            for _ in 0..100 {
                let value = if mode == 0 {
                    super::text_runtime::execute(14222, black_box(&args))
                } else {
                    super::text_runtime::execute_with_regex(14222, black_box(&args), |p| {
                        cache.get(task, p)
                    })
                }
                .unwrap();
                assert_eq!(black_box(value), LuaScalar::Integer(1));
            }
            if round >= 10 {
                samples[mode].push(began.elapsed().as_secs_f64() * 10_000.0);
            }
        }
    }
    for (mode, sample) in samples.iter_mut().enumerate() {
        sample.sort_by(f64::total_cmp);
        println!(
            "PERF_REGEX {}",
            serde_json::json!({"mode":mode,"p50Us":sample[49],"p95Us":sample[94],"p99Us":sample[98],
            "samples":100,"callsPerBatch":100,"scope":"direct Rust regex operation; not script/Root latency"})
        );
    }
}
