# Changelog

## [0.15.0](https://github.com/lumose-health/android-unofficial/compare/v0.14.0...v0.15.0) (2026-09-08)


### ⚠ BREAKING CHANGES

* **pump:** installing an older release after this one erases the app's local database -- on-phone pump history, recorded alerts, anything still queued to upload, and the raw pump records kept for re-derivation. Data already uploaded to your platform is unaffected. Upgrades are safe; there is no downgrade path that preserves local data. See docs/mobile/install.md.

### Features

* **pump:** make poll loops exception-proof and self-restarting ([#43](https://github.com/lumose-health/android-unofficial/issues/43)) ([1d046fe](https://github.com/lumose-health/android-unofficial/commit/1d046feaaeb36b8f334a75d3c5c40b2798555b12))


### Bug Fixes

* commit backfill batches atomically so process death cannot lose derived records ([#47](https://github.com/lumose-health/android-unofficial/issues/47)) ([4db65fc](https://github.com/lumose-health/android-unofficial/commit/4db65fc44e64ee85b1c17bb9fd2e02e4a0356ba5))
* degrade instead of crashing when Android rejects a foreground-service start ([#44](https://github.com/lumose-health/android-unofficial/issues/44)) ([a77e031](https://github.com/lumose-health/android-unofficial/commit/a77e03155873ece6a99bd34f5f86628ae1c5a019))
* **pump:** bound history answers to the window each driver asked for ([#49](https://github.com/lumose-health/android-unofficial/issues/49)) ([b150502](https://github.com/lumose-health/android-unofficial/commit/b1505023cfe61d7404b2e959e5511b210e76c851))
* stop starting the pump service from Application.onCreate ([#46](https://github.com/lumose-health/android-unofficial/issues/46)) ([1e94fab](https://github.com/lumose-health/android-unofficial/commit/1e94fab54d5bdae7cdabca4cd39c5d073a7f1922))
* survive the Android 15 dataSync foreground-service timeout without crashing ([#42](https://github.com/lumose-health/android-unofficial/issues/42)) ([2d9db26](https://github.com/lumose-health/android-unofficial/commit/2d9db2610d2a2b6181a72761f08de51a307fd7b6))

## [0.14.0](https://github.com/lumose-health/android-unofficial/compare/v0.13.0...v0.14.0) (2026-07-28)


### Features

* **mobile:** add an in-app open-source license viewer ([#33](https://github.com/lumose-health/android-unofficial/issues/33)) ([c3de697](https://github.com/lumose-health/android-unofficial/commit/c3de6977cd22ff913c85acf77e7e6e7e03a1ef88))
* **mobile:** attribute redistributed dependencies in the license viewer ([#34](https://github.com/lumose-health/android-unofficial/issues/34)) ([3a3caf2](https://github.com/lumose-health/android-unofficial/commit/3a3caf288d9bebe31d78428c5c396fc642fbf973))


### Bug Fixes

* **mobile:** align phone updater's APK selector to an anchored filename match ([#23](https://github.com/lumose-health/android-unofficial/issues/23)) ([e70d4a9](https://github.com/lumose-health/android-unofficial/commit/e70d4a9a11ed6b18112359128ac8278d25ae8a52))
* **mobile:** repoint self-updater to the canonical org/repo slug ([#31](https://github.com/lumose-health/android-unofficial/issues/31)) ([22a6518](https://github.com/lumose-health/android-unofficial/commit/22a65181e0f8fdd69647ed4139f8e94c373cf5b0))
