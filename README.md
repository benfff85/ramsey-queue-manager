# Ramsey Queue Manager

This service manages the work queue for the Ramsey distributed computing system. It pushes work items to a Redis queue for workers to consume.

## Architecture

```
┌─────────────────┐     ┌───────────┐     ┌─────────────┐
│  Queue Manager  │────▶│   Redis   │◀────│   Workers   │
│  (QueueFeeder)  │     │   Queue   │     │ (pop work)  │
└─────────────────┘     └───────────┘     └─────────────┘
```

## Components

### Queue Feeder

Runs at an interval defined by `ramsey.work-unit.queue.frequency-in-millis` (default: 30 seconds).

- Checks Redis queue depth using O(1) `LLEN` operation
- If depth < `ramsey.work-unit.queue.depth.min`, generates new work items
- Pushes work items to Redis in batches using `LPUSH`
- Workers consume from the other end using `RPOP` (FIFO ordering)

**Configuration:**
- `WORK_UNIT_QUEUE_DEPTH_MIN` - Minimum queue depth before refilling (default: 4M)
- `WORK_UNIT_QUEUE_DEPTH_MAX` - Target queue depth when refilling (default: 8M)
- `WORK_UNIT_ANALYSIS_TYPE` - Type of analysis: TARGETED, COMPREHENSIVE, or NAIVE

### Client Monitor

Monitors client health and marks inactive clients.

- Checks all active clients' last phone home time
- Marks clients as INACTIVE if they haven't phoned home within threshold
- With Redis queue, no work unit reassignment is needed (workers pop directly)

**Configuration:**
- `ramsey.client.registration.timeout.threshold-in-minutes` (default: 5 minutes)

### Client Register

Handles this queue manager instance's registration with the middleware.

## Redis Queue Format

Work items are stored as JSON in a Redis List with key `work_queue:{stageId}`:

```json
{
  "baseGraphId": 1,
  "stageId": 6,
  "edgesToFlip": [{"vertexOne": 12, "vertexTwo": 45}, {"vertexOne": 67, "vertexTwo": 89}],
  "analysisType": "TARGETED"
}
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Redis server hostname | `localhost` |
| `REDIS_PORT` | Redis server port | `6379` |
| `WORK_UNIT_QUEUE_DEPTH_MIN` | Min queue depth | `4000000` |
| `WORK_UNIT_QUEUE_DEPTH_MAX` | Max queue depth | `8000000` |
| `WORK_UNIT_ANALYSIS_TYPE` | Analysis type | `TARGETED` |

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