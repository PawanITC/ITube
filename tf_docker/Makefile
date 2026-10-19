PHONY: echo

up:
	docker volume create postgres_data
	terraform apply -auto-approve

down:
	terraform destroy -auto-approve

nuke: down
	docker volume rm postgres_data