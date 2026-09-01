# AI Agent

An early experiment in building a personal AI agent with access to external information and tools.

This project started as an experiment in combining an LLM with web search, web scraping and OCR. The goal was to make the model more useful by giving it access to information outside the conversation itself.

## What it does

The agent combines several components:

* **LLM integration** — communicates with OpenAI and Anthropic models.
* **Web search** — uses Google Custom Search to retrieve search results.
* **Web scraping** — uses Puppeteer to retrieve content from web pages.
* **OCR** — uses Tesseract to extract text from screenshots and the screen.
* **Context handling** — combines information from different sources before sending it to the model.

One of the experiments was using OCR to read the contents of my VS Code window, allowing the agent to use the code currently visible on screen as context.

## Architecture

At a high level, the agent works as a pipeline:

```text
User
  ↓
LLM
  ↓
Tool / information request
  ├── Google Search
  ├── Web scraping (Puppeteer)
  └── Screen / OCR (Tesseract)
  ↓
Collected context
  ↓
LLM
  ↓
Response
```

The project was built incrementally while I was learning JavaScript, Node.js and API integration. As a result, the repository contains a number of experiments and intermediate implementations rather than a single polished production architecture.

## Technologies

* JavaScript
* Node.js
* OpenAI API
* Anthropic API
* Google Custom Search API
* Puppeteer
* Tesseract OCR

## Background

This was one of my early projects exploring what could be done with LLMs as agents rather than simply using them as chat interfaces.

The project predates many of the agent and computer-use workflows that are common today, and was built primarily as a personal experiment in connecting an LLM to external tools and information sources.

## Status

This is an **archived / experimental project** rather than an actively maintained application.

The repository is kept as a record of the development process and as an example of an early hands-on experiment with LLM agents, APIs, web automation and OCR.
# agent
