macro_rules! id_type {
    ($name:ident, $inner:ty) => {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
        pub struct $name(pub $inner);

        impl $name {
            #[must_use]
            pub const fn get(self) -> $inner {
                self.0
            }
        }
    };
}

id_type!(TaskId, u64);
id_type!(TaskGeneration, u32);
id_type!(RequestId, u64);
id_type!(TimerId, u64);

const RESOURCE_KIND_SHIFT: u32 = 56;
const RESOURCE_LOCAL_MASK: u64 = (1_u64 << RESOURCE_KIND_SHIFT) - 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum ResourceKind {
    Generic = 0,
    Frame = 1,
    Template = 2,
    GlyphDictionary = 3,
    File = 4,
    OcrModel = 5,
    CaptureSeries = 6,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ResourceId(u64);

impl ResourceId {
    #[must_use]
    pub const fn get(self) -> u64 {
        self.0
    }

    #[must_use]
    pub const fn try_new(kind: ResourceKind, local_id: u64) -> Option<Self> {
        if local_id == 0 || local_id > RESOURCE_LOCAL_MASK {
            return None;
        }
        Some(Self(((kind as u64) << RESOURCE_KIND_SHIFT) | local_id))
    }

    #[must_use]
    pub const fn kind(self) -> ResourceKind {
        match (self.0 >> RESOURCE_KIND_SHIFT) as u8 {
            0 => ResourceKind::Generic,
            1 => ResourceKind::Frame,
            2 => ResourceKind::Template,
            3 => ResourceKind::GlyphDictionary,
            4 => ResourceKind::File,
            5 => ResourceKind::OcrModel,
            6 => ResourceKind::CaptureSeries,
            _ => unreachable!(),
        }
    }

    #[must_use]
    pub const fn local_id(self) -> u64 {
        self.0 & RESOURCE_LOCAL_MASK
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct TaskToken {
    pub id: TaskId,
    pub generation: TaskGeneration,
}
