// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jaytech64.piastre.parsing.GoogleWalletParser
import io.github.jaytech64.piastre.parsing.ParserRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ParsingModule {
    /** The one place the app decides which notifications it knows how to read. */
    @Provides
    @Singleton
    fun parserRegistry(): ParserRegistry = ParserRegistry(listOf(GoogleWalletParser()))
}
