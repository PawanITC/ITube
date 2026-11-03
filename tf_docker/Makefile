PHONY: echo

up:
	docker volume create postgres_data
	docker volume create kafka_data
	terraform apply -auto-approve

down:
	terraform destroy -auto-approve

nuke: down
	docker volume rm postgres_data
	docker volume rm kafka_data