use std::collections::{BTreeMap, BTreeSet, VecDeque};

use runtime_scheduler::{MonoTime, TaskToken};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct TransactionId(pub u64);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputCommand {
    Tap {
        x: i32,
        y: i32,
    },
    Swipe {
        from_x: i32,
        from_y: i32,
        to_x: i32,
        to_y: i32,
        duration_ms: u32,
    },
    KeyEvent {
        key_code: u32,
    },
    PointerDown {
        pointer_id: u8,
        x: i32,
        y: i32,
    },
    PointerMove {
        pointer_id: u8,
        x: i32,
        y: i32,
    },
    PointerUp {
        pointer_id: u8,
    },
    Delay {
        milliseconds: u32,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputTransaction {
    pub id: TransactionId,
    pub task: TaskToken,
    pub expires_at: MonoTime,
    pub commands: Vec<InputCommand>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InputArbiterConfig {
    pub max_queued: usize,
    pub max_per_task: usize,
    pub max_commands_per_transaction: usize,
}

impl Default for InputArbiterConfig {
    fn default() -> Self {
        Self {
            max_queued: 128,
            max_per_task: 16,
            max_commands_per_transaction: 1_024,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputError {
    Stopped,
    QueueFull,
    TaskQueueFull(TaskToken),
    EmptyTransaction,
    TooManyCommands,
    InvalidPointerSequence,
    TransactionAlreadyActive,
    NoActiveTransaction,
    TransactionMismatch,
    CommandMismatch,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputDecision {
    Dispatch(InputTransaction),
    Expired(TransactionId),
    Idle,
    Stopped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StopPlan {
    pub discarded: Vec<TransactionId>,
    pub release_pointer_ids: Vec<u8>,
}

#[derive(Debug)]
pub struct InputArbiter {
    config: InputArbiterConfig,
    accepting: bool,
    queued: usize,
    by_task: BTreeMap<TaskToken, VecDeque<InputTransaction>>,
    round_robin: VecDeque<TaskToken>,
    active: Option<InputTransaction>,
    dispatched_index: usize,
    pressed_pointers: BTreeSet<u8>,
}

impl InputArbiter {
    #[must_use]
    pub fn new(config: InputArbiterConfig) -> Self {
        Self {
            config,
            accepting: true,
            queued: 0,
            by_task: BTreeMap::new(),
            round_robin: VecDeque::new(),
            active: None,
            dispatched_index: 0,
            pressed_pointers: BTreeSet::new(),
        }
    }

    /// Enqueues one indivisible transaction in the task-fair bounded queue.
    ///
    /// # Errors
    ///
    /// Returns an error after stop, on capacity limits or invalid pointer sequences.
    pub fn enqueue(&mut self, transaction: InputTransaction) -> Result<(), InputError> {
        if !self.accepting {
            return Err(InputError::Stopped);
        }
        if transaction.commands.is_empty() {
            return Err(InputError::EmptyTransaction);
        }
        if transaction.commands.len() > self.config.max_commands_per_transaction {
            return Err(InputError::TooManyCommands);
        }
        validate_pointer_sequence(&transaction.commands)?;
        if self.queued >= self.config.max_queued {
            return Err(InputError::QueueFull);
        }
        let queue = self.by_task.entry(transaction.task).or_default();
        if queue.len() >= self.config.max_per_task {
            return Err(InputError::TaskQueueFull(transaction.task));
        }
        if queue.is_empty() {
            self.round_robin.push_back(transaction.task);
        }
        queue.push_back(transaction);
        self.queued += 1;
        Ok(())
    }

    /// Selects one whole transaction. Tasks rotate after every selection to prevent starvation.
    ///
    /// # Errors
    ///
    /// Returns an error if a previous transaction is still active.
    pub fn next(&mut self, now: MonoTime) -> Result<InputDecision, InputError> {
        if self.active.is_some() {
            return Err(InputError::TransactionAlreadyActive);
        }
        if !self.accepting {
            return Ok(InputDecision::Stopped);
        }
        loop {
            let Some(task) = self.round_robin.pop_front() else {
                return Ok(InputDecision::Idle);
            };
            let Some(queue) = self.by_task.get_mut(&task) else {
                continue;
            };
            let Some(transaction) = queue.pop_front() else {
                self.by_task.remove(&task);
                continue;
            };
            self.queued = self.queued.saturating_sub(1);
            if queue.is_empty() {
                self.by_task.remove(&task);
            } else {
                self.round_robin.push_back(task);
            }
            if transaction.expires_at <= now {
                return Ok(InputDecision::Expired(transaction.id));
            }
            self.dispatched_index = 0;
            self.active = Some(transaction.clone());
            return Ok(InputDecision::Dispatch(transaction));
        }
    }

    /// Records one command after the backend accepted it, so stop can release live pointers.
    ///
    /// # Errors
    ///
    /// Returns an error for stale transaction IDs or out-of-order command acknowledgements.
    pub fn note_dispatched(
        &mut self,
        transaction: TransactionId,
        command: &InputCommand,
    ) -> Result<(), InputError> {
        let active = self
            .active
            .as_ref()
            .ok_or(InputError::NoActiveTransaction)?;
        if active.id != transaction {
            return Err(InputError::TransactionMismatch);
        }
        if active.commands.get(self.dispatched_index) != Some(command) {
            return Err(InputError::CommandMismatch);
        }
        match command {
            InputCommand::PointerDown { pointer_id, .. } => {
                self.pressed_pointers.insert(*pointer_id);
            }
            InputCommand::PointerUp { pointer_id } => {
                self.pressed_pointers.remove(pointer_id);
            }
            InputCommand::Tap { .. }
            | InputCommand::Swipe { .. }
            | InputCommand::KeyEvent { .. }
            | InputCommand::PointerMove { .. }
            | InputCommand::Delay { .. } => {}
        }
        self.dispatched_index += 1;
        Ok(())
    }

    /// Completes the active transaction after every command was acknowledged.
    ///
    /// # Errors
    ///
    /// Returns an error for stale IDs or incomplete transactions.
    pub fn complete(&mut self, transaction: TransactionId) -> Result<(), InputError> {
        let active = self
            .active
            .as_ref()
            .ok_or(InputError::NoActiveTransaction)?;
        if active.id != transaction {
            return Err(InputError::TransactionMismatch);
        }
        if self.dispatched_index != active.commands.len() {
            return Err(InputError::CommandMismatch);
        }
        self.active = None;
        self.dispatched_index = 0;
        Ok(())
    }

    /// Rejects new input, discards queued work and returns the exact pointer cleanup plan.
    #[must_use]
    pub fn stop(&mut self) -> StopPlan {
        self.accepting = false;
        let mut discarded = self
            .by_task
            .values()
            .flat_map(|queue| queue.iter().map(|transaction| transaction.id))
            .collect::<Vec<_>>();
        if let Some(active) = self.active.take() {
            discarded.push(active.id);
        }
        discarded.sort();
        let release_pointer_ids = self.pressed_pointers.iter().copied().collect();
        self.by_task.clear();
        self.round_robin.clear();
        self.pressed_pointers.clear();
        self.queued = 0;
        self.dispatched_index = 0;
        StopPlan {
            discarded,
            release_pointer_ids,
        }
    }
}

fn validate_pointer_sequence(commands: &[InputCommand]) -> Result<(), InputError> {
    let mut down = BTreeSet::new();
    for command in commands {
        match command {
            InputCommand::PointerDown { pointer_id, .. } if !down.insert(*pointer_id) => {
                return Err(InputError::InvalidPointerSequence);
            }
            InputCommand::PointerMove { pointer_id, .. } if !down.contains(pointer_id) => {
                return Err(InputError::InvalidPointerSequence);
            }
            InputCommand::PointerUp { pointer_id } if !down.remove(pointer_id) => {
                return Err(InputError::InvalidPointerSequence);
            }
            _ => {}
        }
    }
    if down.is_empty() {
        Ok(())
    } else {
        Err(InputError::InvalidPointerSequence)
    }
}

#[cfg(test)]
mod tests {
    use runtime_scheduler::{MonoTime, TaskGeneration, TaskId, TaskToken};

    use super::{
        InputArbiter, InputArbiterConfig, InputCommand, InputDecision, InputError,
        InputTransaction, TransactionId,
    };

    fn task(id: u64) -> TaskToken {
        TaskToken {
            id: TaskId(id),
            generation: TaskGeneration(1),
        }
    }

    fn transaction(id: u64, task: TaskToken) -> InputTransaction {
        InputTransaction {
            id: TransactionId(id),
            task,
            expires_at: MonoTime::from_nanos(100),
            commands: vec![InputCommand::Tap { x: 1, y: 2 }],
        }
    }

    #[test]
    fn task_round_robin_prevents_one_producer_from_starving_another() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        arbiter.enqueue(transaction(1, task(1))).expect("one");
        arbiter.enqueue(transaction(2, task(1))).expect("two");
        arbiter.enqueue(transaction(3, task(2))).expect("three");
        for expected in [TransactionId(1), TransactionId(3), TransactionId(2)] {
            let InputDecision::Dispatch(selected) =
                arbiter.next(MonoTime::ZERO).expect("selection")
            else {
                panic!("transaction expected");
            };
            assert_eq!(selected.id, expected);
            arbiter
                .note_dispatched(selected.id, &selected.commands[0])
                .expect("command");
            arbiter.complete(selected.id).expect("complete");
        }
    }

    #[test]
    fn stop_discards_queue_and_releases_pointer_pressed_mid_transaction() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        let multi = InputTransaction {
            id: TransactionId(7),
            task: task(1),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![
                InputCommand::PointerDown {
                    pointer_id: 4,
                    x: 1,
                    y: 2,
                },
                InputCommand::PointerMove {
                    pointer_id: 4,
                    x: 2,
                    y: 3,
                },
                InputCommand::PointerUp { pointer_id: 4 },
            ],
        };
        arbiter.enqueue(multi).expect("multi");
        arbiter.enqueue(transaction(8, task(2))).expect("queued");
        let InputDecision::Dispatch(active) = arbiter.next(MonoTime::ZERO).expect("active") else {
            panic!("active transaction");
        };
        arbiter
            .note_dispatched(active.id, &active.commands[0])
            .expect("down");
        let plan = arbiter.stop();
        assert_eq!(plan.release_pointer_ids, [4]);
        assert_eq!(plan.discarded, [TransactionId(7), TransactionId(8)]);
        assert_eq!(
            arbiter.enqueue(transaction(9, task(1))),
            Err(InputError::Stopped)
        );
    }

    #[test]
    fn invalid_or_expired_transactions_fail_deterministically() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        let mut invalid = transaction(1, task(1));
        invalid.commands = vec![InputCommand::PointerUp { pointer_id: 1 }];
        assert_eq!(
            arbiter.enqueue(invalid),
            Err(InputError::InvalidPointerSequence)
        );
        arbiter.enqueue(transaction(2, task(1))).expect("valid");
        assert_eq!(
            arbiter.next(MonoTime::from_nanos(100)).expect("expiry"),
            InputDecision::Expired(TransactionId(2))
        );
    }
}
