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

The ``provider_parameters`` of the ``OpenLoadFlowGpu`` load flow provider are the GPU solver's settings
(``pp.loadflow.get_provider_parameters_names('OpenLoadFlowGpu')``); OpenLoadFlow's own parameters keep their
defaults.

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

   Its pom imports the cuDSS-enabled powsybl-core build (``7.4.0-SNAPSHOT``), which must therefore be
   resolvable from the Maven settings pypowsybl is built with. pypowsybl itself keeps powsybl-core 7.3.0 and
   powsybl-math-native 1.5.0: the GPU providers do not use the cuDSS matrix factory of the fork.

3. pypowsybl, as usual (``pip install .``). The versions are the ``powsybl-open-loadflow.version`` and
   ``powsybl-open-loadflow-gpu.version`` properties of ``java/pom.xml``. To build with the Maven settings of
   step 2: ``MAVEN_ARGS="-s /path/to/settings.xml" pip install .``.

``libolfgpu.so`` is embedded in the native image (Linux x86-64 only) and extracted at run time. It links cuDSS
and cuBLAS dynamically: their directories must be on ``LD_LIBRARY_PATH`` (or in the ldconfig cache) when
pypowsybl is imported.
