.PHONY: test test-java test-e2e vet build up down seed demo tidy

test:            ## Go unit tests with the race detector
	go test -race ./...

test-java:       ## Java services: build and tests (Maven)
	cd services/trafficking-api && mvn -B verify
	cd services/adcp-sales-agent && mvn -B verify

test-e2e:        ## Builds both services and runs them against an in-memory Redis
	go test -tags e2e -count=1 ./test/e2e/

vet:
	go vet ./... && go vet -tags e2e ./test/e2e/

build:
	mkdir -p bin
	go build -o bin/adserver-decision ./services/adserver-decision
	go build -o bin/event-collector ./services/event-collector

up:              ## Start Postgres, Redis, Kafka, ClickHouse and the Go services
	docker compose --env-file .env -f infra/docker-compose.yml up -d --build

down:
	docker compose --env-file .env -f infra/docker-compose.yml down

seed:            ## Publish sample line items to local Redis
	go run ./tools/seed -redis localhost:6379

demo:            ## Request one ad from the local stack
	curl -s -i "http://localhost:8081/v1/ad?pub=pub_demo&placement=sports_top&w=300&h=250&uid=demo-user&country=BD&device=mobile"

tidy:
	go mod tidy
