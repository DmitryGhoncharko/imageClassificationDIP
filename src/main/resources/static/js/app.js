document.addEventListener("DOMContentLoaded", () => {
    setupUploadHelpers();
    setupTagEditToggles();
    setupTagQuickSearch();
    setupCropSearch();
    setupAutoTagStatus();
});

function setupUploadHelpers() {
    const fileInput = document.getElementById("uploadFiles");
    const fileCounter = document.getElementById("fileCounter");
    const tagsInput = document.getElementById("uploadTags");
    const tagsPreview = document.getElementById("uploadTagsPreview");

    if (!fileInput || !fileCounter || !tagsInput || !tagsPreview) {
        return;
    }

    fileInput.addEventListener("change", () => {
        const count = fileInput.files ? fileInput.files.length : 0;
        fileCounter.textContent = count > 0 ? `Выбрано файлов: ${count}` : "Файлы не выбраны";
    });

    tagsInput.addEventListener("input", () => {
        const tags = parseTags(tagsInput.value);
        tagsPreview.innerHTML = "";
        tags.forEach((tag) => {
            const chip = document.createElement("span");
            chip.className = "tag-chip";
            chip.textContent = tag;
            tagsPreview.appendChild(chip);
        });
    });
}

function setupTagEditToggles() {
    const toggles = document.querySelectorAll(".js-toggle-tags");
    toggles.forEach((button) => {
        button.addEventListener("click", () => {
            const card = button.closest(".card");
            if (!card) {
                return;
            }
            const form = card.querySelector(".edit-tags-form");
            if (!form) {
                return;
            }
            form.classList.toggle("hidden");
        });
    });
}

function setupTagQuickSearch() {
    const tagButtons = document.querySelectorAll(".tag-chip.interactive");
    const tagInput = document.querySelector('input[name="tag"]');
    const searchForm = document.querySelector(".search-form");
    if (!tagInput || !searchForm || tagButtons.length === 0) {
        return;
    }

    tagButtons.forEach((button) => {
        button.addEventListener("click", () => {
            const tag = button.dataset.tag || "";
            tagInput.value = tag;
            searchForm.submit();
        });
    });
}

function parseTags(rawValue) {
    return rawValue
        .split(",")
        .map((item) => item.trim())
        .filter((item) => item.length > 0)
        .slice(0, 12);
}

function setupCropSearch() {
    const cropToggles = document.querySelectorAll(".js-toggle-crop");
    cropToggles.forEach((toggle) => {
        toggle.addEventListener("click", () => {
            const card = toggle.closest(".card");
            if (!card) {
                return;
            }
            const form = card.querySelector(".crop-search-form");
            if (!form) {
                return;
            }
            form.classList.toggle("hidden");
        });
    });

    const cropBoxes = document.querySelectorAll(".js-crop-box");
    cropBoxes.forEach((box) => initCropBox(box));
}

function initCropBox(box) {
    const image = box.querySelector(".js-crop-image");
    const rect = box.querySelector(".js-crop-rect");
    const xInput = box.parentElement.querySelector(".js-crop-x");
    const yInput = box.parentElement.querySelector(".js-crop-y");
    const wInput = box.parentElement.querySelector(".js-crop-w");
    const hInput = box.parentElement.querySelector(".js-crop-h");

    if (!image || !rect || !xInput || !yInput || !wInput || !hInput) {
        return;
    }

    image.setAttribute("draggable", "false");
    image.addEventListener("dragstart", (event) => event.preventDefault());

    const syncRect = () => updateRectFromInputs(image, rect, xInput, yInput, wInput, hInput);
    if (image.complete) {
        syncRect();
    } else {
        image.addEventListener("load", syncRect, { once: true });
    }
    window.addEventListener("resize", syncRect);

    let isSelecting = false;
    let activePointerId = null;
    let startX = 0;
    let startY = 0;

    box.addEventListener("pointerdown", (event) => {
        if (event.button !== 0) {
            return;
        }
        event.preventDefault();
        isSelecting = true;
        activePointerId = event.pointerId;
        box.setPointerCapture(event.pointerId);
        const bounds = image.getBoundingClientRect();
        startX = event.clientX - bounds.left;
        startY = event.clientY - bounds.top;
        setRectFromPoints(startX, startY, startX, startY, image, rect, xInput, yInput, wInput, hInput);
    });

    box.addEventListener("pointermove", (event) => {
        if (!isSelecting || event.pointerId !== activePointerId) {
            return;
        }
        event.preventDefault();
        const bounds = image.getBoundingClientRect();
        const currentX = event.clientX - bounds.left;
        const currentY = event.clientY - bounds.top;
        setRectFromPoints(startX, startY, currentX, currentY, image, rect, xInput, yInput, wInput, hInput);
    });

    const finishSelection = (event) => {
        if (!isSelecting || event.pointerId !== activePointerId) {
            return;
        }
        event.preventDefault();
        isSelecting = false;
        box.releasePointerCapture(event.pointerId);
        activePointerId = null;
    };

    box.addEventListener("pointerup", finishSelection);
    box.addEventListener("pointercancel", finishSelection);
}

function setRectFromPoints(startX, startY, currentX, currentY, image, rect, xInput, yInput, wInput, hInput) {
    const bounds = image.getBoundingClientRect();
    const left = clamp(Math.min(startX, currentX), 0, bounds.width);
    const top = clamp(Math.min(startY, currentY), 0, bounds.height);
    const right = clamp(Math.max(startX, currentX), 0, bounds.width);
    const bottom = clamp(Math.max(startY, currentY), 0, bounds.height);
    const width = Math.max(8, right - left);
    const height = Math.max(8, bottom - top);

    rect.style.left = `${left}px`;
    rect.style.top = `${top}px`;
    rect.style.width = `${width}px`;
    rect.style.height = `${height}px`;

    const start = elementPointToImageRatio(left, top, image);
    const end = elementPointToImageRatio(left + width, top + height, image);
    xInput.value = start.x.toFixed(4);
    yInput.value = start.y.toFixed(4);
    wInput.value = Math.max(0.01, end.x - start.x).toFixed(4);
    hInput.value = Math.max(0.01, end.y - start.y).toFixed(4);
}

function updateRectFromInputs(image, rect, xInput, yInput, wInput, hInput) {
    const start = imageRatioToElementPoint(
        clamp(Number(xInput.value), 0, 1),
        clamp(Number(yInput.value), 0, 1),
        image
    );
    const end = imageRatioToElementPoint(
        clamp(Number(xInput.value) + Number(wInput.value), 0, 1),
        clamp(Number(yInput.value) + Number(hInput.value), 0, 1),
        image
    );
    const left = Math.min(start.x, end.x);
    const top = Math.min(start.y, end.y);
    const width = Math.max(8, Math.abs(end.x - start.x));
    const height = Math.max(8, Math.abs(end.y - start.y));

    rect.style.left = `${left}px`;
    rect.style.top = `${top}px`;
    rect.style.width = `${width}px`;
    rect.style.height = `${height}px`;
}

function elementPointToImageRatio(x, y, image) {
    const renderedWidth = image.clientWidth;
    const renderedHeight = image.clientHeight;
    const naturalWidth = image.naturalWidth;
    const naturalHeight = image.naturalHeight;
    if (!renderedWidth || !renderedHeight || !naturalWidth || !naturalHeight) {
        return {
            x: clamp(x / Math.max(renderedWidth, 1), 0, 1),
            y: clamp(y / Math.max(renderedHeight, 1), 0, 1)
        };
    }

    const scale = Math.max(renderedWidth / naturalWidth, renderedHeight / naturalHeight);
    const scaledWidth = naturalWidth * scale;
    const scaledHeight = naturalHeight * scale;
    const offsetX = (scaledWidth - renderedWidth) / 2;
    const offsetY = (scaledHeight - renderedHeight) / 2;

    return {
        x: clamp((x + offsetX) / scaledWidth, 0, 1),
        y: clamp((y + offsetY) / scaledHeight, 0, 1)
    };
}

function imageRatioToElementPoint(normX, normY, image) {
    const renderedWidth = image.clientWidth;
    const renderedHeight = image.clientHeight;
    const naturalWidth = image.naturalWidth;
    const naturalHeight = image.naturalHeight;
    if (!renderedWidth || !renderedHeight || !naturalWidth || !naturalHeight) {
        return {
            x: clamp(normX, 0, 1) * renderedWidth,
            y: clamp(normY, 0, 1) * renderedHeight
        };
    }

    const scale = Math.max(renderedWidth / naturalWidth, renderedHeight / naturalHeight);
    const scaledWidth = naturalWidth * scale;
    const scaledHeight = naturalHeight * scale;
    const offsetX = (scaledWidth - renderedWidth) / 2;
    const offsetY = (scaledHeight - renderedHeight) / 2;

    return {
        x: clamp(normX, 0, 1) * scaledWidth - offsetX,
        y: clamp(normY, 0, 1) * scaledHeight - offsetY
    };
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function setupAutoTagStatus() {
    const form = document.querySelector(".js-autotag-form");
    const statusBox = document.getElementById("autoTagStatus");
    const submitButton = form ? form.querySelector(".js-autotag-button") : null;
    if (!form || !statusBox || !submitButton) {
        return;
    }
    const statusUrl = form.dataset.statusUrl;
    if (!statusUrl) {
        return;
    }

    const applyStatus = (status) => {
        const state = status && status.state ? status.state : "IDLE";
        const total = Number(status && status.total ? status.total : 0);
        const processed = Number(status && status.processed ? status.processed : 0);
        const taggedCount = Number(status && status.taggedCount ? status.taggedCount : 0);
        const progress = total > 0 ? Math.min(100, Math.round((processed / total) * 100)) : 0;

        statusBox.className = "banner";
        if (state === "RUNNING") {
            statusBox.classList.add("ok");
            if (processed === 0 && total > 0) {
                statusBox.textContent = `Автотегирование: прогрев модели в Python (${processed}/${total}). Первый запуск может идти несколько минут.`;
            } else {
                statusBox.textContent = `Автотегирование: ${processed}/${total} (${progress}%), протегировано: ${taggedCount}`;
            }
            submitButton.disabled = true;
            submitButton.textContent = "Автотегирование...";
            return;
        }
        submitButton.disabled = false;
        submitButton.textContent = "Автотегировать все непротегированные";
        if (state === "COMPLETED") {
            statusBox.classList.add("ok");
            statusBox.textContent = `Готово: протегировано ${taggedCount} из ${total}`;
            return;
        }
        if (state === "FAILED") {
            statusBox.classList.add("error");
            statusBox.textContent = `Ошибка автотегирования: ${status.message || "неизвестная ошибка"}`;
            return;
        }
        statusBox.classList.add("muted-banner");
        statusBox.textContent = status && status.message ? status.message : "Автотегирование еще не запускалось";
    };

    const pollStatus = async () => {
        try {
            const response = await fetch(statusUrl, { headers: { Accept: "application/json" } });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            const payload = await response.json();
            applyStatus(payload);
        } catch (_error) {
            statusBox.className = "banner error";
            statusBox.textContent = "Не удалось получить статус автотегирования";
            submitButton.disabled = false;
        }
    };

    pollStatus();
    setInterval(pollStatus, 2000);
}
