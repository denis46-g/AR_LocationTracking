/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.codelabs.hellogeospatial.data

import kotlinx.coroutines.flow.Flow

class OfflineAnchorsRepository(private val anchorDao: AnchorDao) : AnchorsRepository {
    override fun getAllAnchorsStream(): Flow<List<Anchor>> = anchorDao.getAllAnchors()

    override fun getAnchorStream(id: Int): Flow<Anchor?> = anchorDao.getAnchor(id)

    override suspend fun insertAnchor(anchor: Anchor) = anchorDao.insert(anchor)

    override suspend fun deleteAnchor(anchor: Anchor) = anchorDao.delete(anchor)

    override suspend fun updateAnchor(anchor: Anchor) = anchorDao.update(anchor)
}
