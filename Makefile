jar:
	clojure -M:jar

deploy: jar
	clojure -Spom
	mvn deploy:deploy-file -Dfile=snoop.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml

test:
	./bin/kaocha

.PHONY: test
