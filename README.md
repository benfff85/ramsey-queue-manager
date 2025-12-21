# Ramsey Queue Manager

This service manages the work queue for the Ramsey distributed computing system. It pushes work items to a Redis queue for workers to consume and handles automatic stage progression when improvements are found.

## Architecture

```
┌─────────────────┐     ┌───────────────┐     ┌─────────────┐
│  Queue Manager  │────▶│     Redis     │◀────│   Workers   │
│  (QueueFeeder)  │     │  work_queue:X │     │ (pop work)  │
│                 │     │  best_result:X│     │ (set best)  │
│  (Progression)  │◀────│               │     │             │
└─────────────────┘     └───────────────┘     └─────────────┘
        │
        ▼
  Creates new stage
```

## Components

### Queue Feeder

Runs at an interval defined by `ramsey.work-unit.queue.frequency-in-millis` (default: 30 seconds).

- Checks Redis queue depth using O(1) `LLEN` operation
- If depth < `ramsey.work-unit.queue.depth.min`, generates new work items
- Pushes work items to Redis in batches using `LPUSH`
- Workers consume from the other end using `RPOP` (FIFO ordering)
- Tracks position in-memory; marks `allWorkCompleted` when all edge combinations generated

### Stage Progression Monitor

Runs at an interval defined by `ramsey.stage-progression.frequency-in-millis` (default: 30 seconds).

- Polls Redis for `best_result:{stageId}` key (set by workers when they find improvements)
- Compares best result's clique count to current base graph
- If better, triggers stage progression:
  1. Gets derived graph from middleware (`GET /graphs/{id}?edgesToFlip=...`)
  2. Saves new graph with improved clique count
  3. Marks current stage INACTIVE
  4. Creates new stage with the improved graph
  5. Clears Redis queue and best_result key

### Client Monitor

Monitors client health and marks inactive clients.

- Checks all active clients' last phone home time
- Marks clients as INACTIVE if they haven't phoned home within threshold

## Redis Key Format

**Work Queue:** `work_queue:{stageId}` (List)
```json
{"baseGraphId": 1, "stageId": 6, "edgesToFlip": [...], "analysisType": "TARGETED"}
```

**Best Result:** `best_result:{stageId}` (String - set by workers)
```json
{"baseGraphId": 6, "stageId": 6, "edgesToFlip": [...], "cliqueCount": 1051000}
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Redis server hostname | `localhost` |
| `REDIS_PORT` | Redis server port | `6379` |
| `WORK_UNIT_QUEUE_DEPTH_MIN` | Min queue depth | `4000000` |
| `WORK_UNIT_QUEUE_DEPTH_MAX` | Max queue depth | `8000000` |
| `WORK_UNIT_ANALYSIS_TYPE` | Analysis type | `TARGETED` |
| `STAGE_PROGRESSION_FREQ` | How often to check for improvements (ms) | `30000` |

## Image Build and Deploy

Build the image:
```bash
docker build -t benferenchak/ramsey-queue-manager:develop .
```

Publish to Dockerhub:
```bash
docker push benferenchak/ramsey-queue-manager:develop
```

Start with Docker Compose (see `ramsey-mw/docker/ramsey-compose.yml`):
```bash
docker compose -f ./docker/ramsey-compose.yml -p ramsey up --scale ramsey-queue-manager=1 -d
```