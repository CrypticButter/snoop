jar:
	clojure -M:skinnyjar

pom:
	clojure -Spom

test:
	./bin/kaocha

chui:
	shadow-cljs -A:lib/cider-nrepl watch test-runner

.PHONY: test
