# Chunking notebooks — one command per ritual.
#
#   make venv      create .venv (uv-managed Python 3.12) + install pins
#   make test      run the property/fixture test suite (pytest + hypothesis)
#   make run       execute BOTH notebooks headless, in place (asserts must pass)
#   make fixture   re-emit fixtures/boundaries.json from the REAL Java engine
#   make lab       open JupyterLab
#   make clean     remove the venv + caches (notebooks untouched)
#
# The host python3 ships without ensurepip and numpy has no wheels for it, so
# the venv uses a uv-managed CPython 3.12 (https://docs.astral.sh/uv/ — on this
# box at ~/.local/bin/uv). All notebook logic lives in prolly_chunking.py;
# `make test && make run` is the full verification.

UV      ?= uv
PYTHON  ?= 3.12
VENV    := .venv
PY      := $(VENV)/bin/python
JUPYTER := $(VENV)/bin/jupyter
ENGINE_JAR ?= $(HOME)/.m2/repository/io/github/prollygraph/dolthub-java-port/0.2.0-BETA/dolthub-java-port-0.2.0-BETA.jar

NOTEBOOKS := production_chunker.ipynb chunking_prolly_trees.ipynb boundary_hashes.ipynb influence_horizon.ipynb merkle_radix_dictionary.ipynb

.PHONY: venv test run fixture lab clean

venv: $(PY)

$(PY): requirements.txt
	$(UV) venv --python $(PYTHON) --clear $(VENV)
	$(UV) pip install -r requirements.txt --python $(PY)
	@$(PY) --version && touch $(PY)

test: $(PY)
	$(PY) -m pytest test_prolly_chunking.py test_merkle_radix_dict.py -q

run: $(PY)
	@for nb in $(NOTEBOOKS); do \
	  echo "== executing $$nb"; \
	  $(JUPYTER) nbconvert --to notebook --execute --inplace $$nb || exit 1; \
	done
	@echo "all notebooks executed clean (every assert passed)"

# Requires a JVM (Java 25) + the engine jar in the local Maven repository
# (build the engine ring once: mvn -pl dolthub-java-port install). Re-run ONLY
# when the splitter changes — a boundary change is format-level news.
fixture:
	java -cp $(ENGINE_JAR) fixtures/EmitBoundaryFixture.java
	@echo "fixtures/boundaries.json refreshed — now: make test"

lab: $(PY)
	$(VENV)/bin/jupyter lab

clean:
	rm -rf $(VENV) __pycache__ .hypothesis .ipynb_checkpoints .pytest_cache
