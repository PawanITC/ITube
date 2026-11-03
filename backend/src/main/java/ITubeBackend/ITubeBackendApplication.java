package ITubeBackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import History.WatchHistory;
import History.HistoryRepository;

@SpringBootApplication(scanBasePackages = {"ITubeBackend", "History"})
@EntityScan(basePackageClasses = WatchHistory.class)
@EnableJpaRepositories(basePackageClasses = HistoryRepository.class)
public class ITubeBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ITubeBackendApplication.class, args);
	}

}
