use super::*;
use pixel_vision::polygon::{ColorSelection, PolygonRegion};

#[derive(Debug, Default)]
pub(super) struct PolygonCache(std::collections::VecDeque<(Vec<u8>, Arc<PolygonRegion>)>);

impl PolygonCache {
    pub(super) fn get(
        &mut self,
        encoded: Option<&[u8]>,
    ) -> Result<Option<Arc<PolygonRegion>>, String> {
        let Some(encoded) = encoded else {
            return Ok(None);
        };
        if encoded.len() > 8192 {
            return Err("多边形配置过长".into());
        }
        if let Some((_, region)) = self.0.iter().find(|(key, _)| key == encoded) {
            return Ok(Some(Arc::clone(region)));
        }
        let points: Vec<[u32; 2]> =
            serde_json::from_slice(encoded).map_err(|_| "多边形顶点格式无效")?;
        let region = Arc::new(
            PolygonRegion::prepare(&points)
                .map_err(|_| "多边形需要3–128个不同顶点，不能交叉、重叠或没有有效像素")?,
        );
        if self.0.len() == 8 {
            self.0.pop_front();
        }
        self.0.push_back((encoded.to_vec(), Arc::clone(&region)));
        Ok(Some(region))
    }
}

pub(super) fn split(args: &[LuaScalar]) -> (&[LuaScalar], Option<&[u8]>) {
    if let Some(LuaScalar::Bytes(bytes)) = args.last() {
        if let Some(json) = bytes.strip_prefix(b"@polygon:") {
            return (&args[..args.len() - 1], Some(json));
        }
    }
    (args, None)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn polygon_geometry_cache_reuses_only_geometry_and_is_bounded() {
        let mut cache = PolygonCache::default();
        let bytes = b"[[0,0],[4,0],[0,4]]";
        let first = cache.get(Some(bytes)).unwrap().unwrap();
        assert!(Arc::ptr_eq(
            &first,
            &cache.get(Some(bytes)).unwrap().unwrap()
        ));
        for n in 5..20 {
            cache
                .get(Some(format!("[[0,0],[{n},0],[0,{n}]]").as_bytes()))
                .unwrap();
        }
        assert_eq!(cache.0.len(), 8);
        assert!(cache.get(None).unwrap().is_none());
        assert!(cache.get(Some(&[b' '; 8193])).is_err());
        assert!(cache.get(Some(b"[[0,0],[4,4],[0,4],[4,0]]")).is_err());
    }
}

impl<C: Clock, H: HostBackend> RuntimeExecutor<C, H> {
    pub(super) fn polygon_color_search(
        &self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
        region: &PolygonRegion,
    ) -> Result<LuaInput, String> {
        let handle = self.scheduler.handle();
        let started = Instant::now();
        let mut cancelled =
            || handle.cancellation_requested(task) || started.elapsed() >= Duration::from_secs(5);
        if opcode == OP_SCREEN_FIND_MULTI_COLOR {
            if args.len() < 8 {
                return Err("invalid multi-color arguments".into());
            }
            let [frame, rgb, tolerance, l, t, r, b, count] = integer_args::<8>(&args[..8])?;
            let count = usize::try_from(count)
                .ok()
                .filter(|n| *n <= MAX_MULTI_COLOR_SAMPLES)
                .ok_or("invalid multi-color sample count")?;
            if args.len() != 8 + count * 4 {
                return Err("multi-color argument count mismatch".into());
            }
            let mut samples = vec![PatternSample {
                offset_x: 0,
                offset_y: 0,
                color: decode_rgb(rgb)?,
                tolerance: decode_tolerance(tolerance)?,
            }];
            for values in args[8..].chunks_exact(4) {
                let [x, y, rgb, tolerance] = integer_args::<4>(values)?;
                samples.push(PatternSample {
                    offset_x: i32::try_from(x).map_err(|_| "invalid sample x")?,
                    offset_y: i32::try_from(y).map_err(|_| "invalid sample y")?,
                    color: decode_rgb(rgb)?,
                    tolerance: decode_tolerance(tolerance)?,
                });
            }
            let view = self.frame_view(task, frame)?;
            let p = pixel_vision::polygon::search_pattern(
                view.image_view().map_err(|e| format!("{e:?}"))?,
                &samples,
                search_options(l, t, r, b)?,
                region,
                &mut cancelled,
            )
            .map_err(|e| format!("{e:?}"))?;
            return Ok(point_input(p.map(|p| (p.x, p.y))));
        }
        let (plain, extra) = if opcode == OP_SCREEN_FIND_COLOR {
            color_group_args::<7>(args)?
        } else {
            color_group_args::<8>(args)?
        };
        let [frame, rgb, tolerance, l, t, r, b] = integer_args::<7>(&plain[..7])?;
        let colors = decode_color_group(rgb, tolerance, extra)?;
        let mode = if opcode == OP_SCREEN_FIND_COLOR {
            ColorSelection::First
        } else {
            let [limit] = integer_args::<1>(&plain[7..])?;
            if opcode == OP_SCREEN_COUNT_COLOR {
                ColorSelection::Count(
                    usize::try_from(limit)
                        .ok()
                        .filter(|n| *n <= pixel_vision::MAX_COLOR_COUNT)
                        .ok_or("invalid color count limit")?,
                )
            } else {
                ColorSelection::All(decode_result_limit(limit)?)
            }
        };
        let view = self.frame_view(task, frame)?;
        let (count, points) = pixel_vision::polygon::search_colors(
            view.image_view().map_err(|e| format!("{e:?}"))?,
            &colors,
            search_options(l, t, r, b)?,
            region,
            mode,
            &mut cancelled,
        )
        .map_err(|e| format!("{e:?}"))?;
        if opcode == OP_SCREEN_FIND_COLOR {
            return Ok(point_input(points.first().map(|p| (p.x, p.y))));
        }
        if opcode == OP_SCREEN_COUNT_COLOR {
            return Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(count as i64),
            ]));
        }
        points_input(&points)
    }
}
