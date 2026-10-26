vendor/ —先行取得した外部ファイル置き場—

lib/:
  byte-buddy 1.18.8 + byte-buddy-agent 1.18.8 (Apache License 2.0)。
  ビルド時に agent jar 内へ unpacked で埋め込む (自己完結 jar)。

※ 公開版 (FSR-UPSC) には DLSS/NGX shim、natives stub、上流 PZ_Optimization
   の記録コピー (旧 vendor/upstream/) は含めていません。テスト版にのみ存在します。
