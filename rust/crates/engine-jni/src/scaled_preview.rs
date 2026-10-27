use jni::{
    objects::{JByteArray, JClass, JIntArray},
    sys::{jint, jlong, jstring},
    JNIEnv,
};
use pixel_vision::{
    ColorTolerance, ImageView, PixelRect, SearchOptions, SearchOrder, TemplateOptions,
};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::{Duration, Instant};

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeTestScaled(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    id: jlong,
    width: jint,
    height: jint,
    frame: JByteArray<'_>,
    template_width: jint,
    template_height: jint,
    template: JByteArray<'_>,
    options: JIntArray<'_>,
) -> jstring {
    let started = Instant::now();
    let result = catch_unwind(AssertUnwindSafe(|| {
        let read = |w: i32, h: i32, data: JByteArray<'_>| -> Result<Vec<u8>, String> {
            if !(1..=4096).contains(&w)
                || !(1..=4096).contains(&h)
                || i64::from(w) * i64::from(h) > 4_194_304
                || env.get_array_length(&data).map_err(|_| "invalid pixels")? != w * h * 4
            {
                return Err("invalid pixels".into());
            }
            env.convert_byte_array(data)
                .map_err(|_| "invalid pixels".into())
        };
        if id <= 0
            || env
                .get_array_length(&options)
                .map_err(|_| "invalid options")?
                != 11
        {
            return Err("invalid options".to_owned());
        }
        let pixels = read(width, height, frame)?;
        let template = read(template_width, template_height, template)?;
        let mut args = [0; 11];
        env.get_int_array_region(options, 0, &mut args)
            .map_err(|_| "invalid options")?;
        let reply = run(
            crate::template_preview::image(width.cast_unsigned(), height.cast_unsigned(), &pixels),
            crate::template_preview::image(
                template_width.cast_unsigned(),
                template_height.cast_unsigned(),
                &template,
            ),
            &args,
            &mut || {
                crate::ocr_preview::is_cancelled(id) || started.elapsed() >= Duration::from_secs(5)
            },
        );
        env.new_string(reply.to_string())
            .map(jni::objects::JString::into_raw)
            .map_err(|_| "result encoding failed".into())
    }));
    crate::ocr_preview::finish(id);
    result
        .ok()
        .and_then(Result::ok)
        .unwrap_or(std::ptr::null_mut())
}

pub(crate) fn run(
    image: ImageView<'_>,
    template: ImageView<'_>,
    args: &[i32],
    cancelled: &mut dyn FnMut() -> bool,
) -> serde_json::Value {
    let result = (|| {
        if args.len() != 11
            || args.iter().any(|v| *v < 0)
            || args[4] > 1000
            || args[5] > 0x00FF_FFFF
            || args[6] > 255
        {
            return Err(pixel_vision::VisionError::InvalidScaleRange);
        }
        // Nonnegative/range checks above retain the old accepted parameter set.
        let tolerance =
            u8::try_from(args[6]).map_err(|_| pixel_vision::VisionError::InvalidScaleRange)?;
        let minimum =
            u16::try_from(args[4]).map_err(|_| pixel_vision::VisionError::InvalidScaleRange)?;
        let order = match args[7] {
            0 => SearchOrder::TopLeftToBottomRight,
            1 => SearchOrder::TopRightToBottomLeft,
            2 => SearchOrder::BottomLeftToTopRight,
            3 => SearchOrder::BottomRightToTopLeft,
            4 => SearchOrder::CenterOut,
            _ => return Err(pixel_vision::VisionError::InvalidScaleRange),
        };
        pixel_vision::scaled_search::find_scaled(
            image,
            template,
            pixel_vision::scaled_search::ScaleRange {
                min_permille: args[8].cast_unsigned(),
                max_permille: args[9].cast_unsigned(),
                step_permille: args[10].cast_unsigned(),
            },
            TemplateOptions {
                search: SearchOptions {
                    roi: PixelRect {
                        left: args[0].cast_unsigned(),
                        top: args[1].cast_unsigned(),
                        right: args[2].cast_unsigned(),
                        bottom: args[3].cast_unsigned(),
                    },
                    step_x: 1,
                    step_y: 1,
                    order,
                    max_pixel_comparisons: 100_000_000,
                },
                tolerance: ColorTolerance {
                    red: tolerance,
                    green: tolerance,
                    blue: tolerance,
                    alpha: 255,
                },
                minimum_match_permille: minimum,
                ignore_transparent_template_pixels: true,
            },
            cancelled,
        )
    })();
    match result {
        Ok(found) => {
            serde_json::json!({"status":i32::from(found.is_none()),"matches":found.map(|v|serde_json::json!({"x":v.matched.origin.x,"y":v.matched.origin.y,"width":v.width,"height":v.height,"scorePermille":v.matched.matched_permille,"scalePermille":v.scale_permille})).into_iter().collect::<Vec<_>>()})
        }
        Err(error) => {
            serde_json::json!({"status":match error {pixel_vision::VisionError::Cancelled=>6,pixel_vision::VisionError::ComparisonBudgetExceeded=>5,_=>2},"matches":[]})
        }
    }
}

#[cfg(test)]
mod tests {
    use super::run;

    #[test]
    fn checked_conversions_preserve_valid_preview_and_reject_invalid_ranges() {
        let pixels = [255; 4];
        let image = crate::template_preview::image(1, 1, &pixels);
        let valid = [0, 0, 1, 1, 1_000, 0x00ff_ffff, 0, 0, 1_000, 1_000, 1];
        assert_eq!(run(image, image, &valid, &mut || false)["status"], 0);
        for (index, value) in [(0, -1), (4, 1_001), (5, 0x0100_0000), (6, 256), (7, 5)] {
            let mut invalid = valid;
            invalid[index] = value;
            assert_eq!(run(image, image, &invalid, &mut || false)["status"], 2);
        }
    }
}
