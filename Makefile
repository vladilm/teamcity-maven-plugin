MODULE ?= tests
TEST ?=
MVN ?= mvn

MAVEN_TEST_ARGS := -pl $(MODULE) -am -Dsurefire.failIfNoSpecifiedTests=false
ifneq ($(strip $(TEST)),)
MAVEN_TEST_ARGS += -Dtest=$(TEST)
endif

.PHONY: test
# Runs Maven tests for the selected module.
# Usage: make test MODULE=tests TEST=IncrementalAssembleCoreTest
# TEST can be omitted to run all tests in MODULE, or include a method selector:
# make test MODULE=tests TEST='IncrementalAssembleCoreTest#missingPreviousStateIsAMiss'
test:
	$(MVN) -q $(MAVEN_TEST_ARGS) test
