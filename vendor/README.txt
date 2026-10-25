vendor/ —先行取得した外部ファイル置き場—

upstream/ (from https://github.com/xD3I/PZ_Optimization master, fetched):
  fsrupsc_ngx.cpp            real shim source (44,623B, sha256 7118CB36...)
    - FSR-UPSC/natives/fsrupsc_ngx.cpp は9関数stub。
    - 実ビルドは必ずこちらを使うこと。13 exports
      (init/error/optimal/create/image_fd/image_bytes/semaphore_fd/
       evaluate/evaluate_set/times/gpu_us/destroy/shutdown) を含む。
  dlss-windows-build.md    MSVC手順の原本 (8,674B)
  dlss-natives.sh          publish用スクリプト (5,692B)
  native-README.md         src/native 説明 (3,263B)

SDK_NOTE.txt:
  NVIDIA DLSS SDK v310.9.1 の取得先と固定sha。バイナリ自体は
  ライセンス上ここに置かない。ビルド時にcloneすること。

更新: 上流が進んだら上記4点を再取得し、fsrupsc_ngx.cpp のshaを
scripts/build 側の期待値と合わせること。
