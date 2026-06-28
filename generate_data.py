import os
import random
import csv
from faker import Faker
from docx import Document
from reportlab.pdfgen import canvas
from datetime import datetime, timedelta

fake = Faker()
Faker.seed(42)

# --- THE NARRATIVE / CASE FILE ---
CASE_NAME = "Operation_Midnight"
TARGET_ENTITIES = {
    "mastermind": "Victor Sterling",
    "logistics": "Elena Rostova",
    "muscle": "Marcus Vance",
    "bank_account": "CA-994-8821-009",
    "target_location": "Pier 4, Warehouse B",
    "stolen_asset": "17th Century Dutch Oil Painting"
}

def create_structure():
    """Creates the physical folder hierarchy for the test case."""
    base_dir = f"./{CASE_NAME}"
    folders = ["Financials", "Communications", "Surveillance", "Legal"]

    if not os.path.exists(base_dir):
        os.makedirs(base_dir)

    for folder in folders:
        path = os.path.join(base_dir, folder)
        if not os.path.exists(path):
            os.makedirs(path)

    return base_dir

def generate_case_brief(base_dir):
    """Generates the starting document for the investigator."""
    filepath = os.path.join(base_dir, "00_INVESTIGATION_BRIEF.txt")
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write("=========================================================\n")
        f.write("RESTRICTED POLICE FILE: OPERATION MIDNIGHT\n")
        f.write("=========================================================\n\n")
        f.write("BACKGROUND:\n")
        f.write("A priceless 17th Century Dutch Oil Painting was stolen from the Metro Museum.\n")
        f.write(f"We suspect billionaire {TARGET_ENTITIES['mastermind']} orchestrated the theft.\n\n")
        f.write("OBJECTIVES FOR THE RAG FORENSIC SYSTEM:\n")
        f.write("1. Find out what bank account Victor used to fund this.\n")
        f.write("2. Identify the 'Logistics' coordinator who arranged the transport.\n")
        f.write("3. Identify the 'Muscle' who physically moved the asset.\n")
        f.write("4. Discover the physical location where the painting is currently hidden.\n\n")
        f.write("INSTRUCTIONS:\n")
        f.write("Create Folders in your UI matching this directory structure.\n")
        f.write("Upload the respective files into their folders.\n")
        f.write("Select different folders to see how the AI isolates context!\n")

def generate_financials(base_dir):
    """Generates CSV files. Only one row contains the golden clue."""
    folder = os.path.join(base_dir, "Financials")
    for i in range(1, 4):
        filepath = os.path.join(folder, f"bank_export_Q{i}.csv")
        with open(filepath, 'w', newline='', encoding='utf-8') as f:
            writer = csv.writer(f)
            writer.writerow(["Transaction_ID", "Date", "Account_From", "Account_To", "Amount", "Notes"])

            for row in range(500):
                # THE GOLDEN CLUE (Only in Q2)
                if i == 2 and row == 250:
                    writer.writerow([fake.uuid4(), fake.date_this_year(), TARGET_ENTITIES["bank_account"], "Elena_Rostova_Offshore", "$50,000", f"Wire Transfer - Payment from {TARGET_ENTITIES['mastermind']}"])
                else:
                    writer.writerow([fake.uuid4(), fake.date_this_year(), fake.iban(), fake.iban(), f"${random.randint(10, 5000)}", fake.word()])

def generate_communications(base_dir):
    """Generates Text files. Contains the logistics and location clues."""
    folder = os.path.join(base_dir, "Communications")
    for i in range(1, 4):
        filepath = os.path.join(folder, f"whatsapp_intercept_device_{i}.txt")
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(f"FORENSIC EXPORT: DEVICE ID #{random.randint(1000, 9999)}\n")
            f.write("=========================================================\n")

            for line in range(1000):
                # THE GOLDEN CLUE (Only in device 3)
                if i == 3 and line == 800:
                    f.write(f"[2026-06-25 23:14:00] {TARGET_ENTITIES['logistics']}: Listen to me, {TARGET_ENTITIES['muscle']}. Take the {TARGET_ENTITIES['stolen_asset']} and secure it at {TARGET_ENTITIES['target_location']}. Do not fail.\n")
                else:
                    f.write(f"[{fake.date_time_this_year()}] {fake.name()}: {fake.sentence()}\n")

def generate_surveillance(base_dir):
    """Generates PDF files using reportlab. Contains sightings of the muscle."""
    folder = os.path.join(base_dir, "Surveillance")
    for i in range(1, 3):
        filepath = os.path.join(folder, f"field_report_unit_{i}.pdf")
        c = canvas.Canvas(filepath)
        c.drawString(100, 800, f"OFFICIAL SURVEILLANCE REPORT - UNIT {i}")
        c.drawString(100, 780, f"Date: {fake.date_this_month()}")

        text = c.beginText(100, 730)
        if i == 1:
            text.textLines(f"Subject {TARGET_ENTITIES['muscle']} was observed pacing outside {TARGET_ENTITIES['target_location']}.")
            text.textLines("He was seen carrying a large, rectangular crate matching the dimensions of a painting.")
        else:
            text.textLines(f"Routine observation. {fake.paragraph(nb_sentences=5)}")

        text.textLines(f"Additional notes: {fake.paragraph(nb_sentences=10)}")
        c.drawText(text)
        c.save()

def generate_legal(base_dir):
    """Generates DOCX files. Warrants linking the mastermind."""
    folder = os.path.join(base_dir, "Legal")
    filepath = os.path.join(folder, "search_warrant_001.docx")

    doc = Document()
    doc.add_heading('Search Warrant Authorized', 0)
    doc.add_paragraph(f"By order of the court, accounts linked to {TARGET_ENTITIES['bank_account']} are frozen.")
    doc.add_paragraph(f"These accounts are confirmed to be the primary slush fund of {TARGET_ENTITIES['mastermind']}.")
    doc.add_paragraph(fake.text(max_nb_chars=1000))
    doc.save(filepath)

if __name__ == "__main__":
    print("Initializing Forensic Data Generator...")
    base = create_structure()

    generate_case_brief(base)
    generate_financials(base)
    generate_communications(base)
    generate_surveillance(base)
    generate_legal(base)

    print(f"\nSUCCESS! Generated highly structured investigation case in: ./{CASE_NAME}")
    print("Folders created: Financials, Communications, Surveillance, Legal")
    print("Read '00_INVESTIGATION_BRIEF.txt' for your instructions.")