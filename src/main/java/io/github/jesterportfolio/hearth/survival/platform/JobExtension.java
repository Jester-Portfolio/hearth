package io.github.jesterportfolio.hearth.survival.platform;
/** 확장 플러그인은 onLoad에서 Bukkit ServicesManager에 등록한다. */
@FunctionalInterface public interface JobExtension {void install(JobRuntime runtime);}
