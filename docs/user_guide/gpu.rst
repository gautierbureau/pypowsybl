GPU load flow and security analysis
===================================

.. currentmodule:: pypowsybl

This build of pypowsybl bundles the ``OpenLoadFlowGpu`` providers of
`powsybl-open-loadflow-gpu <https://github.com/gautierbureau/olf-gpu>`_:

- a load flow provider: OpenLoadFlow's load flow with the AC Newton-Raphson on the GPU (cuDSS);
- a security analysis provider: N-1 contingencies solved as GPU batches.

They are selected by provider name:

.. code-block:: python

   >>> import pypowsybl as pp
   >>> n = pp.network.create_ieee14()
   >>> results = pp.loadflow.run_ac(n, provider='OpenLoadFlowGpu')
   >>> sa = pp.security.create_analysis()
   >>> sa.add_single_element_contingency('L1-2-1', 'L1-2-1')
   >>> sa_result = sa.run_ac(n, provider='OpenLoadFlowGpu')

The ``provider_parameters`` of the ``OpenLoadFlowGpu`` load flow provider are OpenLoadFlow's, plus the GPU
solver's settings (``pp.loadflow.get_provider_parameters_names('OpenLoadFlowGpu')``):

.. code-block:: python

   >>> p = pp.loadflow.Parameters(provider_parameters={'maxNewtonRaphsonIterations': '30',
   ...                                                 'reorderingAlgorithm': 'AMD'})
   >>> results = pp.loadflow.run_ac(n, p, provider='OpenLoadFlowGpu')

The ``OpenLoadFlowGpu`` security analysis provider runs its load flows with OpenLoadFlow's parameters, as the
``OpenLoadFlow`` one does.

Without a GPU, or without the native library, the load flow provider fails with
*GPU Newton-Raphson requested but the native library is not available*.

Build
-----

These providers are not released: pypowsybl must be built from source, after installing its two
dependencies in the local Maven repository.

1. OpenLoadFlow from the ``olf-local-patches`` branch of
   `gautierbureau/powsybl-open-loadflow <https://github.com/gautierbureau/powsybl-open-loadflow>`_
   (version ``2.4.0-SNAPSHOT-gpu``):

   .. code-block:: bash

      git clone -b olf-local-patches https://github.com/gautierbureau/powsybl-open-loadflow.git
      cd powsybl-open-loadflow
      mvn -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip -Dlicense.skip install

2. powsybl-open-loadflow-gpu (version ``1.0.0-SNAPSHOT``), its native library first so that
   ``libolfgpu.so`` is packaged in the jar (needs the CUDA toolkit and cuDSS, see ``native/build-gpu.sh``):

   .. code-block:: bash

      git clone https://github.com/gautierbureau/olf-gpu.git
      cd olf-gpu
      CUDSS_ROOT=/path/to/cudss native/build-gpu.sh
      mvn -DskipTests install

   It builds against the cuDSS-enabled powsybl-core (``7.4.0-SNAPSHOT``, ``cudss_factory`` branch of
   `gautierbureau/powsybl-core <https://github.com/gautierbureau/powsybl-core>`_) and powsybl-math-native
   (``1.6.0-SNAPSHOT-gpu``, ``cudss_solver_1`` branch of
   `gautierbureau/powsybl-math-native <https://github.com/gautierbureau/powsybl-math-native>`_, built with
   ``-DWITH_CUDSS=ON`` so its jar carries ``libmathcudss.so``), which must be installed first.

3. pypowsybl. Two choices:

   - ``pip install .``: pypowsybl keeps its powsybl-core 7.3.0 and powsybl-math-native 1.5.0. The GPU providers
     work: they do not use the cuDSS matrix factory of the core fork. The core fork's pom must still be
     resolvable, as olf-gpu's pom imports it.
   - ``PYPOWSYBL_CUDSS=1 pip install .``: builds on the same cuDSS-enabled powsybl-core and powsybl-math-native
     as olf-gpu (maven profile ``cudss`` of ``java/pom.xml``) and ships ``libmathcudss.so`` next to
     ``libmath.so``, so ``CuDssMatrixFactory`` is available too.

   The versions are the ``powsybl-open-loadflow.version``, ``powsybl-open-loadflow-gpu.version``,
   ``powsybl-core.version`` and ``powsybl-math-native.version`` properties of ``java/pom.xml`` (the last two set
   by the ``cudss`` profile). To build with other Maven settings:
   ``MAVEN_ARGS="-s /path/to/settings.xml" pip install .``.

``libolfgpu.so`` is embedded in the native image (Linux x86-64 only) and extracted at run time;
``libmathcudss.so`` is installed next to ``libmath.so``. Both link cuDSS and cuBLAS dynamically: their
directories must be on ``LD_LIBRARY_PATH`` (or in the ldconfig cache) when pypowsybl is imported.
