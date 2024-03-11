# Ramsey Queue Manager
Whereas the middleware is primarily responsible simply for fetching and persisting data, this queue manager is responsible for the management of the system state. This is comprised of the following:

## Queue Feeder

This class runs at an interval defined by `ramsey.work-unit.queue.frequency-in-millis` (default: 30 seconds) and ensures there are sufficient work units queued up.

If the graphId of the minimum graph has changed cancel all open WorkUnits. 

Pull all unassigned work units and check if the number is less than the minimum queue depth `ramsey.work-unit.queue.depth.min` (default: 2500), if so, create enough work units to hit the max queue depth `ramsey.work-unit.queue.depth.min` (default: 5000).

## Client Assignment Manager

This class is responsible for assigning work units to clients. It begins by pulling all active cliquechecker clients. 

For each client it then pulls the work units currently assigned, if the count is less than the desired count defined by `ramsey.work-unit.assignment.count-per-client` (default: 500) then pull unassigned work units and assign them to the client such that the count reaches the desired number of work units.

## Client Monitor

This class is responsible for deregistering clients which have not phoned home for a given duration defined by `ramsey.client.registration.timeout.threshold-in-minutes` (default: 5 minutes).

It pulls all clients and if the phone home is greater than 5 minutes ago it will flip it to inactive status. Likewise when flipping a client to inactive status it will unassign any currently assigned but not yet completed work units.