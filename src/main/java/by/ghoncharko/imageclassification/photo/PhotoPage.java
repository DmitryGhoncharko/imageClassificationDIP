package by.ghoncharko.imageclassification.photo;

import java.util.List;

public class PhotoPage {
    private final List<Photo> items;
    private final int page;
    private final int size;
    private final int totalPages;
    private final long totalItems;

    public PhotoPage(List<Photo> items, int page, int size, int totalPages, long totalItems) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalPages = totalPages;
        this.totalItems = totalItems;
    }

    public List<Photo> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public long getTotalItems() {
        return totalItems;
    }

    public boolean isHasPrevious() {
        return page > 0;
    }

    public boolean isHasNext() {
        return page + 1 < totalPages;
    }
}
