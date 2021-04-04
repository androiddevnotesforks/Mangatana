package com.aredruss.mangatana.view

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aredruss.mangatana.App
import com.aredruss.mangatana.data.database.MediaDb
import com.aredruss.mangatana.repo.DatabaseRepository
import com.aredruss.mangatana.repo.JikanRepository
import com.aredruss.mangatana.view.media.info.DetailsState
import com.aredruss.mangatana.view.media.list.ListState
import com.aredruss.mangatana.view.util.ScreenCategory
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch

class MainViewModel(
    private val jikanRepository: JikanRepository,
    private val databaseRepository: DatabaseRepository
) : AndroidViewModel(App.INSTANCE), LifecycleObserver {

    var listState = MutableLiveData<ListState>()
    var detailsState = MutableLiveData<DetailsState>()
    var screenCategory = -1
    var mediaType: String = JikanRepository.TYPE_MANGA

    // Get Content for the list Screen
    // Need to check what type of content list should be populated with
    fun getMediaList(tabType: String?, screenCategory: Int) {
        // Category of content differs -> the list should be fully reloaded
        if (this.screenCategory != screenCategory) {
            this.screenCategory = screenCategory
            getMedia(mediaType, screenCategory)
            return
        }
        // The fragment's selected tab wasn't changed -> if the list is already populated we don't need to reload it
        // If the tab was, in fact, changed, the list should be reloaded
        if (tabType == null) {
            if (listState.value is ListState.Success) {
                if ((listState.value as ListState.Success).payload.isNullOrEmpty()) {
                    getMedia(mediaType, screenCategory)
                }
            }
        } else if (tabType != mediaType) {
            mediaType = tabType
            getMedia(mediaType, screenCategory)
        }
    }

    // Get Media either from the database or the API depending on the category of the screen
    private fun getMedia(type: String, screenCategory: Int) {
        when (screenCategory) {
            ScreenCategory.ON_GOING -> getSavedMedia(MediaDb.ONGOING_STATUS, type)
            ScreenCategory.BACKLOG -> getSavedMedia(MediaDb.BACKLOG_STATUS, type)
            ScreenCategory.FINISHED -> getSavedMedia(MediaDb.FINISHED_STATUS, type)
            ScreenCategory.STARRED -> getFavoriteMedia(type)
            else -> getTopMedia(type)
        }
    }

    // Get Media from the Jikan API
    private fun getTopMedia(type: String) = viewModelScope.launch {
        jikanRepository.getTopMediaList(type).onStart {
            listState.postValue(ListState.Loading)
        }.catch { e ->
            listState.postValue(ListState.Error(e))
        }.collect { topMedia ->
            listState.postValue(ListState.Success(topMedia))
        }
    }

    // Get Info about a certain media piece - a manga series or an anime
    fun getMediaDetails(type: String, malId: Long) = viewModelScope.launch {
        jikanRepository.getMedia(type, malId)
            .onStart {
                detailsState.postValue(DetailsState.Loading)
            }
            .zip(databaseRepository.getMediaEntry(malId, type)) { mediaResponse, mediaDb ->
                detailsState.postValue(DetailsState.Success(mediaResponse, mediaDb))
            }.catch { e ->
                detailsState.postValue(DetailsState.Error(e))
            }.collect()
    }

    // Save media with a certain status or update an existing entry
    fun editMediaEntry(status: Int, isStarred: Boolean) = viewModelScope.launch {
        if (detailsState.value is DetailsState.Success) {
            val successState = detailsState.value as DetailsState.Success
            if (successState.localEntry != null) {
                with(successState.localEntry) {
                    databaseRepository.updateMediaEntry(this.malId, status, isStarred, mediaType)
                }
                detailsState.postValue(
                    successState.apply {
                        localEntry?.status = status
                        localEntry?.isStarred = isStarred
                    }
                )
            } else {
                databaseRepository.insertMediaEntry(
                    successState.payload,
                    mediaType,
                    status,
                    isStarred
                )
                databaseRepository.getMediaEntry(successState.payload.malId, mediaType)
                    .catch { e -> detailsState.postValue(DetailsState.Error(e)) }
                    .collect {
                        detailsState.postValue(DetailsState.Success(successState.payload, it))
                    }
            }
        }
    }

    fun deleteMediaEntry(mediaId: Long) = viewModelScope.launch {
        if (detailsState.value is DetailsState.Success) {
            val successPayload = (detailsState.value as DetailsState.Success).payload
            databaseRepository.deleteMediaEntry(mediaId, mediaType)
            detailsState.postValue(DetailsState.Success(successPayload, null))
        }
    }

    // Debug function as if now - clears all entries from the database
    fun clearDatabase() = viewModelScope.launch {
        databaseRepository.clear()
    }

    fun cancelJobs() {
        jikanRepository.cancelAll()
        databaseRepository.cancelAll()
    }

    // Get Media from the Database
    private fun getSavedMedia(status: Int, type: String) = viewModelScope.launch {
        databaseRepository.getSavedMediaList(status, type)
            .onStart {
                listState.postValue(ListState.Loading)
            }
            .catch { e ->
                listState.postValue(ListState.Error(e))
            }
            .collect { list ->
                listState.postValue(
                    if (list.isEmpty()) {
                        ListState.Empty
                    } else {
                        ListState.Success(list as ArrayList<MediaDb>)
                    }
                )
            }
    }

    private fun getFavoriteMedia(type: String) = viewModelScope.launch {
        databaseRepository.getFavoriteMediaList(type)
            .onStart {
                listState.postValue(ListState.Loading)
            }
            .catch { e ->
                listState.postValue(ListState.Error(e))
            }
            .collect { list ->
                listState.postValue(
                    if (list.isEmpty()) {
                        ListState.Empty
                    } else {
                        ListState.Success(list as ArrayList<MediaDb>)
                    }
                )
            }
    }
}
