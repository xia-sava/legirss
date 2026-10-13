package to.sava.legirss.ui

/** OPML のファイルを、利用者に場所を選ばせて読み書きする。 */
interface OpmlFiles {
    /**
     * 読み込むファイルを選ばせて、その中身を返す。選ばずに閉じたら null。
     *
     * @throws kotlinx.io.IOException ファイルを読めない。
     */
    suspend fun pick(): ByteArray?

    /**
     * 保存先を選ばせて、[text] を書き出す。
     *
     * @param suggestedName 保存先を選ぶ画面に、最初に入れておくファイル名。
     * @return 書き出したら true。選ばずに閉じたら false。
     * @throws kotlinx.io.IOException ファイルに書けない。
     */
    suspend fun save(suggestedName: String, text: String): Boolean
}
