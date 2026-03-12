document.addEventListener("DOMContentLoaded", () => {
    const cryptoPanel = document.querySelector("[data-crypto-panel]");
    const toggleButtons = document.querySelectorAll("[data-crypto-toggle]");
    const networkSelect = document.querySelector("[data-network-select]");
    const networkLabel = document.querySelector("[data-network-label]");
    const networkShort = document.querySelector("[data-network-short]");
    const countdownDisplay = document.querySelector("[data-countdown-display]");
    const statusBadge = document.querySelector("[data-crypto-status]");
    const explorerLink = document.querySelector("[data-explorer-link]");
    const explorerLabel = document.querySelector("[data-explorer-label]");
    const regenerateButton = document.querySelector("[data-regenerate-invoice]");
    const transactionHashInput = document.querySelector("[data-transaction-hash]");
    const hashFeedback = document.querySelector("[data-hash-feedback]");
    const submitHashButton = document.querySelector("[data-submit-hash]");
    const copyWalletButton = document.querySelector("[data-copy-wallet]");
    const copyAddressFullButton = document.querySelector("[data-copy-address-full]");
    const copyWalletLabel = document.querySelector("[data-copy-label]");
    const walletAddress = document.querySelector("[data-wallet-address]");
    const initialCountdownSeconds = 1800;

    if (!cryptoPanel) {
        return;
    }

    let countdownSeconds = initialCountdownSeconds;
    let timerId = null;

    const explorers = {
        "USDT (TRC20)": { label: "View on Tronscan", baseUrl: "https://tronscan.org/#/transaction/", fallbackUrl: "https://tronscan.org/" },
        "USDT (ERC20)": { label: "View on Etherscan", baseUrl: "https://etherscan.io/tx/", fallbackUrl: "https://etherscan.io/" },
        "USDC": { label: "View on Etherscan", baseUrl: "https://etherscan.io/tx/", fallbackUrl: "https://etherscan.io/" },
        BTC: { label: "View on Blockchain.com", baseUrl: "https://www.blockchain.com/explorer/transactions/btc/", fallbackUrl: "https://www.blockchain.com/explorer" },
        ETH: { label: "View on Etherscan", baseUrl: "https://etherscan.io/tx/", fallbackUrl: "https://etherscan.io/" }
    };

    const networkValidators = {
        "USDT (TRC20)": /^T[a-zA-Z0-9]{20,63}$/i,
        "USDT (ERC20)": /^0x[a-fA-F0-9]{64}$/,
        "USDC": /^0x[a-fA-F0-9]{64}$/,
        BTC: /^[a-fA-F0-9]{64}$/,
        ETH: /^0x[a-fA-F0-9]{64}$/
    };

    const setStatus = (label, statusClass) => {
        if (!statusBadge) {
            return;
        }

        statusBadge.textContent = label;
        statusBadge.className = `invoice-status ${statusClass}`;
    };

    const formatCountdown = (seconds) => {
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = seconds % 60;
        return `${String(minutes).padStart(2, "0")}:${String(remainingSeconds).padStart(2, "0")}`;
    };

    const currentNetwork = () => networkSelect?.value || "USDT (TRC20)";

    const currentNetworkShort = () => {
        const match = currentNetwork().match(/\(([^)]+)\)/);
        return match ? match[1] : currentNetwork();
    };

    const updateExplorerLink = (hash = "") => {
        if (!explorerLink || !explorerLabel) {
            return;
        }

        const config = explorers[currentNetwork()] || explorers["USDT (TRC20)"];
        explorerLabel.textContent = config.label;
        explorerLink.href = hash ? `${config.baseUrl}${hash}` : config.fallbackUrl;
    };

    const updateNetworkPresentation = () => {
        if (networkLabel) {
            networkLabel.textContent = currentNetwork();
        }

        if (networkShort) {
            networkShort.textContent = currentNetworkShort();
        }

        updateExplorerLink(transactionHashInput?.value.trim() || "");
    };

    const updateFeedback = (message, stateClass) => {
        if (!hashFeedback) {
            return;
        }

        hashFeedback.textContent = message;
        hashFeedback.className = `hash-feedback mt-2 ${stateClass}`;
    };

    const stopTimer = () => {
        if (timerId) {
            window.clearInterval(timerId);
            timerId = null;
        }
    };

    const tickTimer = () => {
        if (!countdownDisplay) {
            return;
        }

        if (countdownSeconds <= 0) {
            countdownDisplay.textContent = "Expired";
            setStatus("Expired", "is-expired");
            updateFeedback("Invoice expired. Generate a new invoice before submitting a transaction hash.", "is-warning");
            regenerateButton?.classList.remove("d-none");
            submitHashButton?.setAttribute("disabled", "disabled");
            stopTimer();
            return;
        }

        countdownDisplay.textContent = formatCountdown(countdownSeconds);
        countdownSeconds -= 1;
    };

    const startTimer = () => {
        stopTimer();
        tickTimer();
        timerId = window.setInterval(tickTimer, 1000);
    };

    const resetInvoice = () => {
        countdownSeconds = initialCountdownSeconds;
        regenerateButton?.classList.add("d-none");
        submitHashButton?.removeAttribute("disabled");
        if (transactionHashInput) {
            transactionHashInput.value = "";
        }
        updateFeedback("Paste transaction hash to validate format and explorer lookup.", "");
        setStatus("Pending", "is-pending");
        updateExplorerLink("");
        startTimer();
    };

    const openPanel = () => {
        cryptoPanel.classList.remove("d-none");
        document.body.classList.add("crypto-modal-open");
        resetInvoice();
    };

    const closePanel = () => {
        cryptoPanel.classList.add("d-none");
        document.body.classList.remove("crypto-modal-open");
        stopTimer();
    };

    const setCopyButtonState = (label, styleClass) => {
        if (!copyWalletButton) {
            return;
        }

        copyWalletButton.classList.remove("btn-outline-secondary", "btn-success", "btn-danger");
        copyWalletButton.classList.add(styleClass);
        copyWalletButton.setAttribute("title", label === "Copied" ? "Copied" : "Copy wallet address");
        copyWalletButton.setAttribute("aria-label", label === "Copied" ? "Copied" : "Copy wallet address");
        if (copyWalletLabel) {
            copyWalletLabel.textContent = label;
        }

        if (copyAddressFullButton) {
            copyAddressFullButton.classList.remove("btn-outline-secondary", "btn-success", "btn-danger");
            copyAddressFullButton.classList.add(styleClass);
            copyAddressFullButton.innerHTML = label === "Copied"
                    ? '<i class="bi bi-check2 me-1"></i>Copied'
                    : '<i class="bi bi-copy me-1"></i>Copy Address';
        }
    };

    toggleButtons.forEach((button) => {
        button.addEventListener("click", () => {
            const action = button.getAttribute("data-crypto-toggle");
            if (action === "open") {
                openPanel();
                return;
            }
            closePanel();
        });
    });

    regenerateButton?.addEventListener("click", resetInvoice);

    const copyWalletAddress = async () => {
        const address = walletAddress?.textContent?.trim() || "";
        if (!address) {
            setCopyButtonState("Error", "btn-danger");
            return;
        }

        try {
            await navigator.clipboard.writeText(address);
            setCopyButtonState("Copied", "btn-success");
            window.setTimeout(() => setCopyButtonState("Copy", "btn-outline-secondary"), 1600);
        } catch {
            setCopyButtonState("Error", "btn-danger");
            window.setTimeout(() => setCopyButtonState("Copy", "btn-outline-secondary"), 1600);
        }
    };

    copyWalletButton?.addEventListener("click", copyWalletAddress);
    copyAddressFullButton?.addEventListener("click", copyWalletAddress);

    networkSelect?.addEventListener("change", () => {
        updateNetworkPresentation();
        updateFeedback(`Network updated. Make sure funds are sent on ${currentNetworkShort()}.`, "is-warning");
    });

    transactionHashInput?.addEventListener("input", () => {
        const hash = transactionHashInput.value.trim();
        updateExplorerLink(hash);

        if (!hash) {
            updateFeedback("Paste transaction hash to validate format and explorer lookup.", "");
            setStatus("Pending", "is-pending");
            return;
        }

        const validator = networkValidators[currentNetwork()];
        if (validator && !validator.test(hash)) {
            updateFeedback("Invalid transaction hash for the selected network.", "is-error");
            setStatus("Failed", "is-failed");
            return;
        }

        updateFeedback("Transaction hash format looks valid. Submit to continue confirmation checks.", "is-success");
        setStatus("Pending", "is-pending");
    });

    submitHashButton?.addEventListener("click", () => {
        const hash = transactionHashInput?.value.trim() || "";
        const validator = networkValidators[currentNetwork()];

        if (!hash) {
            updateFeedback("Paste a transaction hash before submitting.", "is-error");
            setStatus("Failed", "is-failed");
            return;
        }

        if (validator && !validator.test(hash)) {
            updateFeedback("Invalid transaction hash. Check the selected network and try again.", "is-error");
            setStatus("Failed", "is-failed");
            return;
        }

        if (countdownSeconds <= 0) {
            updateFeedback("Transaction not found because the invoice has expired. Generate a new invoice.", "is-warning");
            setStatus("Expired", "is-expired");
            return;
        }

        updateFeedback("Transaction hash submitted. Awaiting blockchain confirmation.", "is-success");
        setStatus("Awaiting Confirmation", "is-awaiting");
        updateExplorerLink(hash);
    });

    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && !cryptoPanel.classList.contains("d-none")) {
            closePanel();
        }
    });

    updateNetworkPresentation();
});
